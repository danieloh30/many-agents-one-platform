# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# ./demo.sh build — jars + SPA, then images.
#   openshift:  ImageStreams + BuildConfigs (deploy/overlays/openshift/build), one binary Docker
#               build per service from its module directory, in parallel; images referenced by the
#               digest the build produced.
#   kubernetes: podman (or docker) build + push to MAOP_REGISTRY, tag = short git SHA[-dirty].
# The resulting references are recorded in .maop/images-<platform>-<namespace>.env.

container_engine() {
  if [[ -n "${MAOP_CONTAINER_ENGINE:-}" ]]; then echo "$MAOP_CONTAINER_ENGINE"
  elif have podman; then echo podman
  elif have docker; then echo docker
  else echo ""; fi
}

build_local_artifacts() {
  [[ -x "${REPO_ROOT}/mvnw" ]] || die "./mvnw not found; the Maven build (B-01) is required before images can be built"
  log "building the SPA (web/: npm ci && npm run build)"
  (cd "${REPO_ROOT}/web" && npm ci --no-audit --no-fund && npm run build)
  log "building the services (./mvnw -B -q package -DskipTests)"
  (cd "$REPO_ROOT" && ./mvnw -B -q package -DskipTests)
  local svc
  for svc in $MAOP_SERVICES; do
    [[ -d "${REPO_ROOT}/${svc}/target/quarkus-app" ]] || die "${svc}/target/quarkus-app missing after the Maven build"
    [[ -f "${REPO_ROOT}/${svc}/src/main/docker/Dockerfile.jvm" ]] || die "${svc}/src/main/docker/Dockerfile.jvm missing"
  done
}

write_image_record() {
  local f svc var
  f="$(image_record_file)"
  {
    printf '# Written by ./demo.sh build at %s — image references deployed by ./demo.sh up\n' "$(utc_now)"
    for svc in $MAOP_SERVICES; do
      var="IMAGE_$(printf '%s' "$svc" | tr 'a-z-' 'A-Z_')"
      printf '%s=%s\n' "$var" "${!var}"
    done
  } > "$f"
  log "image references recorded in ${f#"$REPO_ROOT"/}"
}

ensure_namespace() {
  case "$(namespace_ownership)" in
    foreign) die "namespace '$MAOP_NAMESPACE' exists but is not managed by this project; choose another --namespace";;
    absent) log "creating namespace $MAOP_NAMESPACE";;
    unreachable) die "cannot reach the cluster to check namespace '$MAOP_NAMESPACE'";;
  esac
  # Pod Security "restricted" everywhere in the namespace (docs/security.md §8).
  # On OpenShift, label sync is turned off for this namespace so the explicit labels stay as set.
  local sync="true"
  [[ "$MAOP_PLATFORM" == "openshift" ]] && sync="false"
  "$KC" create namespace "$MAOP_NAMESPACE" --dry-run=client -o json \
    | jq --arg mb "$MAOP_MANAGED_BY" --arg po "$MAOP_PART_OF" --arg sync "$sync" '.metadata.labels = ({
        "app.kubernetes.io/managed-by": $mb,
        "app.kubernetes.io/part-of": $po,
        "pod-security.kubernetes.io/enforce": "restricted",
        "pod-security.kubernetes.io/enforce-version": "latest",
        "pod-security.kubernetes.io/warn": "restricted",
        "pod-security.kubernetes.io/audit": "restricted"}
        + (if $sync == "false" then {"security.openshift.io/scc.podSecurityLabelSync": "false"} else {} end))' \
    | "$KC" apply -f - >/dev/null
}

build_openshift_images() {
  local tag svc pids="" logdir failed=""
  tag="$(git_image_tag)"
  logdir="${MAOP_STATE_DIR}/build-logs"; mkdir -p "$logdir"
  log "applying ImageStreams and BuildConfigs"
  oc apply -n "$MAOP_NAMESPACE" -k "${REPO_ROOT}/deploy/overlays/openshift/build" >/dev/null
  log "starting in-cluster binary builds (logs: ${logdir#"$REPO_ROOT"/}/<service>.log)"
  for svc in $MAOP_SERVICES; do
    ( oc start-build "$svc" -n "$MAOP_NAMESPACE" --from-dir="${REPO_ROOT}/${svc}" --follow --wait \
        > "${logdir}/${svc}.log" 2>&1 ) &
    pids="$pids $!:$svc"
  done
  local entry pid
  for entry in $pids; do
    pid="${entry%%:*}"; svc="${entry#*:}"
    if wait "$pid"; then info "build $svc: done"; else failed="$failed $svc"; err "build $svc failed; last lines:"; tail -n 20 "${logdir}/${svc}.log" >&2 || true; fi
  done
  [[ -z "$failed" ]] || die "image build failed for:$failed"
  local ref var
  for svc in $MAOP_SERVICES; do
    ref="$(oc get istag "maop-${svc}:latest" -n "$MAOP_NAMESPACE" -o jsonpath='{.image.dockerImageReference}')"
    [[ "$ref" == *@sha256:* ]] || die "could not resolve the digest of maop-${svc}:latest"
    # Human-readable tag next to the digest (traceability to the commit).
    oc tag "${MAOP_NAMESPACE}/maop-${svc}@${ref#*@}" "${MAOP_NAMESPACE}/maop-${svc}:${tag}" >/dev/null
    var="IMAGE_$(printf '%s' "$svc" | tr 'a-z-' 'A-Z_')"
    printf -v "$var" '%s' "$ref"
  done
  write_image_record
}

build_kubernetes_images() {
  [[ -n "$MAOP_REGISTRY" ]] || die "MAOP_REGISTRY is required for --platform kubernetes (e.g. quay.io/<org>)"
  local engine tag svc image ref var digestfile
  engine="$(container_engine)"; [[ -n "$engine" ]] || die "podman or docker is required"
  tag="$(git_image_tag)"
  for svc in $MAOP_SERVICES; do
    image="${MAOP_REGISTRY%/}/maop-${svc}:${tag}"
    log "building $image ($engine, ${MAOP_BUILD_PLATFORM})"
    "$engine" build --platform "$MAOP_BUILD_PLATFORM" -f "${REPO_ROOT}/${svc}/src/main/docker/Dockerfile.jvm" -t "$image" "${REPO_ROOT}/${svc}"
    log "pushing $image"
    ref="$image"
    if [[ "$engine" == "podman" ]]; then
      digestfile="$(maop_tmpdir)/digest-${svc}"
      podman push --digestfile "$digestfile" "$image"
      [[ -s "$digestfile" ]] && ref="${MAOP_REGISTRY%/}/maop-${svc}@$(cat "$digestfile")"
    else
      docker push "$image"
      local rd
      rd="$(docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image" 2>/dev/null | grep -F "${MAOP_REGISTRY%/}/maop-${svc}@" | head -n1 || true)"
      [[ -n "$rd" ]] && ref="$rd"
    fi
    var="IMAGE_$(printf '%s' "$svc" | tr 'a-z-' 'A-Z_')"
    printf -v "$var" '%s' "$ref"
  done
  write_image_record
}

# Called by `build` and by `up` (unless --skip-build).
do_build() {
  build_local_artifacts
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    ensure_namespace
    build_openshift_images
  else
    build_kubernetes_images
  fi
}

cmd_build() {
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    confirm_target "build (creates/updates ImageStreams, BuildConfigs and builds in the namespace)"
  fi
  do_build
}
