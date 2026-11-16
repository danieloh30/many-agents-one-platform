# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# Cluster-scoped platform components (KEDA, SPIRE). Installed only with
# --install-platform-components and only when absent; every install is recorded in ConfigMap
# maop-installed-components in the app namespace so `down --uninstall-platform-components`
# removes exactly what this project installed.

record_installed_component() {
  local key="$1" value="$2"
  "$KC" create configmap maop-installed-components -n "$MAOP_NAMESPACE" --dry-run=client -o json \
    | jq --arg mb "$MAOP_MANAGED_BY" '.metadata.labels = {"app.kubernetes.io/name": "maop-installed-components", "app.kubernetes.io/managed-by": $mb}' \
    | "$KC" apply -f - >/dev/null
  "$KC" patch configmap maop-installed-components -n "$MAOP_NAMESPACE" --type merge \
    -p "$(jq -n --arg k "$key" --arg v "$value" '{data: {($k): $v}}')" >/dev/null
}

helm_repo_ensure() {
  local name="$1" url="$2"
  helm repo add "$name" "$url" --force-update >/dev/null
  helm repo update "$name" >/dev/null
}

install_keda() {
  log "installing KEDA chart ${KEDA_CHART} ${KEDA_CHART_VERSION} into namespace ${KEDA_NAMESPACE}"
  helm_repo_ensure "$KEDA_CHART_REPO_NAME" "$KEDA_CHART_REPO_URL"
  helm upgrade --install "$KEDA_RELEASE" "${KEDA_CHART_REPO_NAME}/${KEDA_CHART}" \
    --version "$KEDA_CHART_VERSION" -n "$KEDA_NAMESPACE" --create-namespace \
    -f "${REPO_ROOT}/deploy/platform/keda/values.yaml" --wait --timeout 10m
  record_installed_component keda "helm:${KEDA_NAMESPACE}/${KEDA_RELEASE}@${KEDA_CHART_VERSION}"
}

install_spire() {
  local td="$1" cluster_name="$2"
  log "installing SPIRE charts (${SPIRE_CRDS_CHART} ${SPIRE_CRDS_CHART_VERSION}, ${SPIRE_CHART} ${SPIRE_CHART_VERSION}); trust domain ${td}"
  helm_repo_ensure "$SPIRE_CHART_REPO_NAME" "$SPIRE_CHART_REPO_URL"
  helm upgrade --install "$SPIRE_CRDS_RELEASE" "${SPIRE_CHART_REPO_NAME}/${SPIRE_CRDS_CHART}" \
    --version "$SPIRE_CRDS_CHART_VERSION" -n "$SPIRE_RELEASE_NAMESPACE" --create-namespace --wait
  record_installed_component spire-crds "helm:${SPIRE_RELEASE_NAMESPACE}/${SPIRE_CRDS_RELEASE}@${SPIRE_CRDS_CHART_VERSION}"
  local values=(-f "${REPO_ROOT}/deploy/platform/spire/values.yaml")
  [[ "$MAOP_PLATFORM" == "openshift" ]] && values+=(-f "${REPO_ROOT}/deploy/platform/spire/values-openshift.yaml")
  spire_chart() {
    helm upgrade --install "$SPIRE_RELEASE" "${SPIRE_CHART_REPO_NAME}/${SPIRE_CHART}" \
      --version "$SPIRE_CHART_VERSION" -n "$SPIRE_RELEASE_NAMESPACE" "${values[@]}" \
      --set-string "global.spire.trustDomain=${td}" \
      --set-string "global.spire.clusterName=${cluster_name}" \
      --set-string "spire-server.controllerManager.className=${SPIRE_CLASS_NAME}" \
      --set-string "spiffe-csi-driver.image.tag=${SPIFFE_CSI_DRIVER_TAG}" \
      --wait --timeout 15m
  }
  # On a fresh OpenShift cluster Helm creates the CSIDriver (an unordered kind) after the Deployments,
  # so admission rejects pods that mount csi.spiffe.io inline until the labelled CSIDriver exists.
  # Observed on OpenShift 4.22: the first attempt fails, a retry once the CSIDriver exists succeeds.
  if ! spire_chart; then
    "$KC" get csidriver csi.spiffe.io >/dev/null 2>&1 || die "SPIRE chart install failed (see above)"
    warn "SPIRE chart failed before its CSIDriver existed; retrying now that csi.spiffe.io is present"
    spire_chart || die "SPIRE chart install failed on retry (see above)"
  fi
  record_installed_component spire "helm:${SPIRE_RELEASE_NAMESPACE}/${SPIRE_RELEASE}@${SPIRE_CHART_VERSION}"
  record_installed_component spire-trust-domain "$td"

  # deployment.md §5.2 item 2: restricted pods may mount csi.spiffe.io only when the CSIDriver
  # carries the restricted ephemeral-volume profile. The chart sets it with global.openshift=true;
  # enforce it in case a chart change drops it.
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    local profile
    profile="$("$KC" get csidriver csi.spiffe.io -o jsonpath='{.metadata.labels.security\.openshift\.io/csi-ephemeral-volume-profile}' 2>/dev/null || true)"
    if [[ "$profile" != "restricted" ]]; then
      log "labelling CSIDriver csi.spiffe.io with csi-ephemeral-volume-profile=restricted"
      "$KC" label csidriver csi.spiffe.io security.openshift.io/csi-ephemeral-volume-profile=restricted --overwrite
    fi
  fi
}

# Applies the platform plan computed by plan_platform. Needs the namespace (for the record).
ensure_platform() {
  case "$PLAN_KEDA_ACTION" in
    install) install_keda;;
    reuse-cma) log "KEDA: Custom Metrics Autoscaler present -> openshift-cma overlay (prometheus trigger); upstream KEDA is never installed next to CMA";;
    reuse-upstream) log "KEDA: reusing ${KEDA_DETAIL}";;
    *) die "KEDA not available: ${PLAN_PROBLEMS}";;
  esac
  case "$PLAN_SPIRE_ACTION" in
    install)
      local cname="${MAOP_SPIRE_CLUSTER_NAME:-}"
      if [[ -z "$cname" && "$MAOP_PLATFORM" == "openshift" ]]; then cname="$(openshift_infra_name)"; fi
      install_spire "$PLAN_TRUST_DOMAIN" "${cname:-maop-cluster}";;
    reuse) log "SPIRE: reusing existing install (SPIRE_MODE=existing): trust domain ${PLAN_TRUST_DOMAIN}, className '${PLAN_CLASS_NAME:-<none>}'";;
    *) die "SPIRE not available: ${PLAN_PROBLEMS}";;
  esac
}

# down --uninstall-platform-components: only what the record lists.
uninstall_recorded_platform() {
  local rec
  rec="$(installed_components_get spire)"
  if [[ -n "$rec" ]]; then
    log "uninstalling SPIRE release ${SPIRE_RELEASE} (installed by this project: $rec)"
    helm uninstall "$SPIRE_RELEASE" -n "$SPIRE_RELEASE_NAMESPACE" --wait || warn "helm uninstall $SPIRE_RELEASE failed"
  fi
  rec="$(installed_components_get spire-crds)"
  if [[ -n "$rec" ]]; then
    log "uninstalling ${SPIRE_CRDS_RELEASE} (installed by this project: $rec)"
    helm uninstall "$SPIRE_CRDS_RELEASE" -n "$SPIRE_RELEASE_NAMESPACE" --wait || warn "helm uninstall $SPIRE_CRDS_RELEASE failed"
    # The chart creates these namespaces (global.spire.namespaces.create); remove them if empty of releases.
    "$KC" delete namespace "$SPIRE_RELEASE_NAMESPACE" --ignore-not-found --wait=false >/dev/null || true
  fi
  rec="$(installed_components_get keda)"
  if [[ -n "$rec" ]]; then
    log "uninstalling KEDA release ${KEDA_RELEASE} (installed by this project: $rec)"
    helm uninstall "$KEDA_RELEASE" -n "$KEDA_NAMESPACE" --wait || warn "helm uninstall $KEDA_RELEASE failed"
    "$KC" delete namespace "$KEDA_NAMESPACE" --ignore-not-found --wait=false >/dev/null || true
  fi
  "$KC" delete configmap maop-installed-components -n "$MAOP_NAMESPACE" --ignore-not-found >/dev/null || true
}
