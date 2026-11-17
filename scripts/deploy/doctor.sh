# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# ./demo.sh doctor — read-only checks. Every cluster call is get/list/auth can-i/version.

D_STATUS=(); D_NAME=(); D_DETAIL=()
d_add() { D_STATUS+=("$1"); D_NAME+=("$2"); D_DETAIL+=("$3"); }
d_pass() { d_add PASS "$1" "$2"; }
d_warn() { d_add WARN "$1" "$2"; }
d_fail() { d_add FAIL "$1" "$2"; }

d_print() {
  local i color fails=0 warns=0
  printf '\n%-6s %-34s %s\n' "RESULT" "CHECK" "DETAIL"
  printf '%-6s %-34s %s\n' "------" "----------------------------------" "------"
  for ((i = 0; i < ${#D_STATUS[@]}; i++)); do
    case "${D_STATUS[i]}" in
      PASS) color="$C_GRN";; WARN) color="$C_YLW"; warns=$((warns + 1));; *) color="$C_RED"; fails=$((fails + 1));;
    esac
    printf '%s%-6s%s %-34s %s\n' "$color" "${D_STATUS[i]}" "$C_RST" "${D_NAME[i]}" "${D_DETAIL[i]}"
  done
  printf '\n%d checks: %d FAIL, %d WARN\n' "${#D_STATUS[@]}" "$fails" "$warns"
  DOCTOR_FAILS=$fails
}

doctor_local_tools() {
  local v
  if have java; then
    v="$(java -version 2>&1 | head -n1 | sed -E 's/.*version "([^"]+)".*/\1/')"
    if version_ge "${v%%[-+]*}" 25; then d_pass "java >= 25" "java $v"; else d_fail "java >= 25" "found java $v; JDK 25 or newer is required (CLAUDE.md rule 1)"; fi
  else
    d_fail "java >= 25" "java not found on PATH"
  fi
  # The minimum comes from web/package.json (engines.node), which the upgrade-compat toolchain check keeps current.
  local node_min
  node_min="$(jq -r '.engines.node // ">=0"' "${REPO_ROOT}/web/package.json" 2>/dev/null)"; node_min="${node_min#>=}"
  if have node; then
    v="$(node --version 2>/dev/null)"; v="${v#v}"
    if version_ge "$v" "$node_min"; then d_pass "node >= $node_min" "node $v"; else d_fail "node >= $node_min" "found node $v (web/.nvmrc, engines.node in web/package.json)"; fi
  else
    d_fail "node >= $node_min" "node not found on PATH"
  fi
  if have npm; then d_pass "npm" "npm $(npm --version 2>/dev/null)"; else d_fail "npm" "npm not found"; fi
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    if have oc; then d_pass "oc" "$(oc version --client 2>/dev/null | head -n1)"; else d_fail "oc" "oc is required for --platform openshift"; fi
  else
    if have kubectl || have oc; then d_pass "kubectl/oc" "using $KC"; else d_fail "kubectl/oc" "kubectl or oc required"; fi
  fi
  if have helm; then d_pass "helm" "$(helm version --short 2>/dev/null)"; else
    if [[ "${OPT_INSTALL_PLATFORM}" == "true" ]]; then d_fail "helm" "helm is required to install KEDA/SPIRE"; else d_warn "helm" "not found (only needed with --install-platform-components)"; fi
  fi
  if [[ "$MAOP_PLATFORM" == "kubernetes" ]]; then
    if have podman || have docker; then d_pass "podman or docker" "$(container_engine)"; else d_fail "podman or docker" "needed to build and push images for --platform kubernetes"; fi
    if [[ -n "$MAOP_REGISTRY" ]]; then d_pass "MAOP_REGISTRY" "$MAOP_REGISTRY"; else d_fail "MAOP_REGISTRY" "set MAOP_REGISTRY (e.g. quay.io/<org>) in .env.local for --platform kubernetes"; fi
  fi
  local t missing=""
  for t in jq curl git openssl; do have "$t" || missing="$missing $t"; done
  if [[ -z "$missing" ]]; then d_pass "jq curl git openssl" "present"; else d_fail "jq curl git openssl" "missing:$missing"; fi
  if [[ -x "${REPO_ROOT}/mvnw" ]]; then d_pass "maven wrapper" "./mvnw present"; else d_warn "maven wrapper" "./mvnw not found yet (needed by build)"; fi
}

doctor_model() {
  case "$MAOP_MODEL_MODE" in
    deterministic) d_warn "model mode" "deterministic (explicit, labelled in the console; no model calls)";;
    live)
      local missing=""
      [[ -n "$MAOP_MODEL_BASE_URL" ]] || missing="$missing MAOP_MODEL_BASE_URL"
      [[ -n "$MAOP_MODEL_NAME" ]] || missing="$missing MAOP_MODEL_NAME"
      [[ -n "${MAOP_MODEL_API_KEY:-}" ]] || missing="$missing MAOP_MODEL_API_KEY"
      if [[ -n "$missing" ]]; then
        d_fail "model config (live)" "missing in .env.local:$missing (or set MAOP_MODEL_MODE=deterministic)"
        return 0
      fi
      d_pass "model config (live)" "base URL and model name set, API key present (not shown)"
      if [[ "$OPT_SKIP_MODEL_PROBE" == "true" ]]; then
        d_warn "model endpoint probe" "skipped (--skip-model-probe)"; return 0
      fi
      local tmp hdr code url
      tmp="$(maop_tmpdir)"; hdr="$tmp/model-auth-header"
      ( umask 077; printf 'Authorization: Bearer %s\n' "$MAOP_MODEL_API_KEY" > "$hdr" )
      url="${MAOP_MODEL_BASE_URL%/}/models"
      code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -H "@$hdr" "$url" 2>/dev/null || true)"
      rm -f "$hdr"
      case "$code" in
        200) d_pass "model endpoint probe" "GET <base>/models -> 200 from this workstation";;
        401|403) d_fail "model endpoint probe" "GET <base>/models -> $code: the API key was rejected";;
        000|"") d_warn "model endpoint probe" "no HTTP response from <base>/models (network/DNS/TLS); cluster egress is checked by verify";;
        *) d_warn "model endpoint probe" "GET <base>/models -> $code (endpoint may not implement /models)";;
      esac;;
  esac
}

doctor_cluster() {
  # Login.
  local who=""
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    if who="$(oc whoami 2>/dev/null)"; then d_pass "cluster login" "$who @ $(kube_server_url)"; else
      d_fail "cluster login" "oc whoami failed; log in with 'oc login' as a cluster administrator"; return 1; fi
  else
    if who="$("$KC" auth whoami -o jsonpath='{.status.userInfo.username}' 2>/dev/null)" || "$KC" get --raw /version >/dev/null 2>&1; then
      d_pass "cluster login" "${who:-authenticated} @ $(kube_server_url)"
    else
      d_fail "cluster login" "cannot reach the cluster with context $(kube_context_name)"; return 1
    fi
  fi

  # Server version.
  local minor gitv ocpv=""
  gitv="$("$KC" version -o json 2>/dev/null | jq -r '.serverVersion.gitVersion // empty' || true)"
  minor="$("$KC" version -o json 2>/dev/null | jq -r '.serverVersion.minor // empty' | tr -cd '0-9' || true)"
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    ocpv="$(oc get clusterversion version -o jsonpath='{.status.desired.version}' 2>/dev/null || true)"
    if [[ -z "$ocpv" ]]; then d_warn "OpenShift version" "could not read clusterversion (kubernetes ${gitv:-unknown})"
    elif version_ge "$ocpv" 4.21; then d_pass "OpenShift version" "$ocpv (kubernetes ${gitv:-?})"
    else d_warn "OpenShift version" "$ocpv is below the supported minimum 4.21 (docs/versions.md §5)"; fi
  else
    if [[ -n "$minor" ]] && ((minor >= 34)); then d_pass "Kubernetes version" "$gitv"
    else d_warn "Kubernetes version" "${gitv:-unknown}; tested range is 1.34+ (docs/versions.md §5)"; fi
  fi

  # Cluster-scoped permissions (only relevant when installing platform components).
  local res missing=""
  for res in customresourcedefinitions clusterroles clusterrolebindings namespaces; do
    "$KC" auth can-i create "$res" >/dev/null 2>&1 || missing="$missing $res"
  done
  [[ "$MAOP_PLATFORM" == "openshift" ]] && { oc auth can-i create securitycontextconstraints.security.openshift.io >/dev/null 2>&1 || missing="$missing securitycontextconstraints"; }
  if [[ -z "$missing" ]]; then d_pass "cluster-scoped create (can-i)" "allowed"
  elif [[ "$OPT_INSTALL_PLATFORM" == "true" ]]; then d_fail "cluster-scoped create (can-i)" "not allowed:$missing — needed by --install-platform-components; log in as cluster-admin"
  else d_warn "cluster-scoped create (can-i)" "not allowed:$missing (only needed with --install-platform-components)"; fi
  if "$KC" auth can-i create clusterspiffeids.spire.spiffe.io >/dev/null 2>&1; then
    d_pass "create ClusterSPIFFEID (can-i)" "allowed"
  else
    d_warn "create ClusterSPIFFEID (can-i)" "not allowed (or CRD absent yet); up applies one ClusterSPIFFEID"
  fi

  # Default StorageClass.
  local sc
  sc="$("$KC" get storageclass -o json 2>/dev/null | jq -r '[.items[] | select(.metadata.annotations["storageclass.kubernetes.io/is-default-class"]=="true") | .metadata.name] | join(",")' || true)"
  if [[ -n "$sc" ]]; then d_pass "default StorageClass" "$sc"; else d_fail "default StorageClass" "none; NATS and PostgreSQL PVCs need a default StorageClass (docs/deployment.md §3)"; fi

  # Internal registry (OpenShift builds).
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    local reg
    reg="$(oc get configs.imageregistry.operator.openshift.io cluster -o jsonpath='{.spec.managementState}' 2>/dev/null || true)"
    case "$reg" in
      Managed) d_pass "internal image registry" "Managed";;
      "") d_warn "internal image registry" "state unknown (cannot read configs.imageregistry.operator.openshift.io)";;
      *) d_fail "internal image registry" "managementState=$reg; in-cluster builds need the internal registry";;
    esac
  fi

  # Platform plan.
  plan_platform
  local kd="$KEDA_DETAIL"
  case "$PLAN_KEDA_ACTION" in
    reuse-cma) d_pass "KEDA / CMA" "$kd -> openshift-cma overlay (prometheus trigger)";;
    reuse-upstream) d_pass "KEDA / CMA" "$kd -> reuse";;
    install) d_pass "KEDA / CMA" "none -> will install chart keda ${KEDA_CHART_VERSION} into ${KEDA_NAMESPACE}";;
    upgrade) d_pass "KEDA / CMA" "$kd -> will upgrade chart keda ${PLAN_KEDA_FROM} -> ${KEDA_CHART_VERSION} (installed by this project)";;
    blocked) d_fail "KEDA / CMA" "$kd";;
  esac
  case "$PLAN_SPIRE_ACTION" in
    reuse) d_pass "SPIRE" "existing (${SPIRE_DETAIL}); trust domain ${PLAN_TRUST_DOMAIN}; className '${PLAN_CLASS_NAME:-<none>}'"
      [[ -z "$PLAN_CLASS_NAME" ]] && d_warn "SPIRE controller class" "no className detected; set MAOP_SPIRE_CLASS_NAME if the existing controller manager ignores classless ClusterSPIFFEIDs";;
    install)
      if [[ "$SPIRE_STATE" == "incomplete" ]]; then
        d_pass "SPIRE" "${SPIRE_DETAIL} -> will repair (${SPIRE_CHART} ${SPIRE_CHART_VERSION}); trust domain ${PLAN_TRUST_DOMAIN}"
      else
        d_pass "SPIRE" "none -> will install ${SPIRE_CRDS_CHART} ${SPIRE_CRDS_CHART_VERSION} + ${SPIRE_CHART} ${SPIRE_CHART_VERSION}; trust domain ${PLAN_TRUST_DOMAIN}"
      fi;;
    upgrade) d_pass "SPIRE" "existing -> will upgrade ${SPIRE_CHART} ${PLAN_SPIRE_FROM} -> ${SPIRE_CHART_VERSION} (installed by this project); trust domain ${PLAN_TRUST_DOMAIN}";;
    blocked) d_fail "SPIRE" "${SPIRE_DETAIL}";;
  esac
  local line
  while IFS= read -r line; do [[ -n "$line" ]] && info "-> $line"; done <<< "$PLAN_PROBLEMS"

  # Namespace ownership.
  case "$(namespace_ownership)" in
    absent) d_pass "namespace $MAOP_NAMESPACE" "absent (up will create it)";;
    owned) d_pass "namespace $MAOP_NAMESPACE" "exists, managed by this project";;
    foreign) d_fail "namespace $MAOP_NAMESPACE" "exists without label app.kubernetes.io/managed-by=$MAOP_MANAGED_BY; choose another --namespace";;
    *) d_fail "namespace $MAOP_NAMESPACE" "cannot read the namespace (permissions or connectivity)";;
  esac
  return 0
}

# Runs all checks; returns non-zero on any FAIL. Used by doctor and (fail fast) by up.
run_doctor() {
  D_STATUS=(); D_NAME=(); D_DETAIL=()
  log "doctor: platform=$MAOP_PLATFORM namespace=$MAOP_NAMESPACE (read-only)"
  doctor_local_tools
  doctor_model
  doctor_cluster || true
  d_print
  ((DOCTOR_FAILS == 0))
}

cmd_doctor() { run_doctor; }
