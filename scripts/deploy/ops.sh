# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# status, open, logs, reset, token, down.

cmd_status() {
  require_owned_namespace
  log "workloads in namespace $MAOP_NAMESPACE ($(kube_server_url))"
  kn get deploy,statefulset -l "${MAOP_OWNED_SELECTOR}" -o wide || true
  printf '\n'
  kn get pods -l "app.kubernetes.io/part-of=${MAOP_PART_OF}" -o wide || true
  printf '\n'
  log "autoscaling"
  kn get scaledobject evidence-agent 2>/dev/null || warn "ScaledObject evidence-agent not found (KEDA CRD missing?)"
  kn get hpa 2>/dev/null || true
  printf '\n'
  log "exposure"
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then kn get route 2>/dev/null || true; else kn get ingress 2>/dev/null || true; fi
  printf '\n'
  log "platform components"
  detect_keda; info "KEDA: ${KEDA_STATE} — ${KEDA_DETAIL}"
  detect_spire; info "SPIRE: ${SPIRE_STATE} — ${SPIRE_DETAIL}${SPIRE_DETECTED_TRUST_DOMAIN:+ (trust domain ${SPIRE_DETECTED_TRUST_DOMAIN})}"
  local rec
  rec="$("$KC" get configmap maop-installed-components -n "$MAOP_NAMESPACE" -o json 2>/dev/null | jq -r '.data // {} | to_entries[] | "\(.key)=\(.value)"' || true)"
  info "installed by this project: ${rec:-nothing recorded}"
  if "$KC" get clusterspiffeid "maop-${MAOP_NAMESPACE}" >/dev/null 2>&1; then
    info "ClusterSPIFFEID maop-${MAOP_NAMESPACE}: present"
  else
    warn "ClusterSPIFFEID maop-${MAOP_NAMESPACE}: missing"
  fi
  printf '\n'
  log "last runs (console API)"
  resolve_console
  local out="${MAOP_TMP}/runs.json"
  console_api GET "/api/v1/runs?limit=5" "$out"
  if [[ "$API_CODE" == "200" ]]; then
    jq -r '.items[]? | [.createdAt, .runId, .scenario, .state, (.outcome // "-")] | @tsv' "$out" \
      | awk 'BEGIN{FS="\t"; printf "  %-22s %-38s %-14s %-18s %s\n","CREATED","RUN","SCENARIO","STATE","OUTCOME"} {printf "  %-22s %-38s %-14s %-18s %s\n",$1,$2,$3,$4,$5}'
  else
    warn "GET /api/v1/runs returned HTTP ${API_CODE}"
  fi
}

cmd_open() {
  require_owned_namespace
  local name url
  for name in ops-console jaeger grafana; do
    url="$(route_url "$name")"
    printf '%-12s %s\n' "$name" "${url:-<not exposed>}"
    if [[ -n "$url" && "$(uname -s)" == "Darwin" ]] && have open; then open "$url"; fi
  done
}

cmd_logs() {
  require_owned_namespace
  local svc="${1:-}"
  if [[ -n "$svc" ]]; then
    case " $MAOP_SERVICES nats postgres otel-collector jaeger prometheus grafana " in
      *" $svc "*) ;; *) die "unknown service: $svc";;
    esac
    local kind=deploy
    [[ "$svc" == "nats" || "$svc" == "postgres" ]] && kind=statefulset
    kn logs -f "${kind}/${svc}" --all-containers --prefix --tail=200
  else
    kn logs -f -l "app.kubernetes.io/part-of=${MAOP_PART_OF}" --all-containers --prefix \
      --max-log-requests=40 --tail=50
  fi
}

cmd_reset() {
  require_owned_namespace
  confirm_target "reset (deletes demonstration state: runs, audit, simulated actions, queued messages)"
  local out="${MAOP_TMP}/reset.json"
  console_api POST /api/v1/admin/reset "$out" - auth
  case "$API_CODE" in
    2*) log "demo state reset (HTTP ${API_CODE}). evidence-agent replicas are left to KEDA to settle.";;
    409) die "reset refused (HTTP 409): a burst is active — wait for it to finish or stop it in the console";;
    *) die "reset failed: HTTP ${API_CODE} $(head -c 300 "$out" 2>/dev/null)";;
  esac
}

cmd_token() {
  require_owned_namespace
  local token
  token="$(kn get secret ops-console-presenter -o jsonpath='{.data.token}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "$token" ]] || die "Secret ops-console-presenter not found"
  printf '%sPresenter token for %s (keep it off screen):%s\n' "$C_DIM" "$MAOP_NAMESPACE" "$C_RST" >&2
  printf '%s\n' "$token"
}

# Kinds owned by the app. Secrets and PVCs only with --delete-data.
_owned_kinds() {
  local kinds="scaledobjects.keda.sh deployments statefulsets jobs services configmaps serviceaccounts roles rolebindings networkpolicies"
  [[ "$MAOP_PLATFORM" == "openshift" ]] && kinds="$kinds routes.route.openshift.io buildconfigs.build.openshift.io builds.build.openshift.io imagestreams.image.openshift.io"
  [[ "$MAOP_PLATFORM" == "kubernetes" ]] && kinds="$kinds ingresses.networking.k8s.io"
  printf '%s\n' "$kinds"
}

cmd_down() {
  case "$(namespace_ownership)" in
    absent) log "namespace $MAOP_NAMESPACE does not exist; nothing to remove"
      [[ "$OPT_UNINSTALL_PLATFORM" == "true" ]] && warn "no install record available (it lived in the namespace); platform components left untouched"
      return 0;;
    foreign) die "namespace '$MAOP_NAMESPACE' is not managed by this project; refusing to touch it";;
    unreachable) die "cannot reach the cluster ($(kube_server_url))";;
  esac
  local what="delete app resources (label ${MAOP_OWNED_SELECTOR}); keep PVCs and Secrets"
  [[ "$OPT_DELETE_DATA" == "true" ]] && what="delete app resources AND PVCs + Secrets (data loss)"
  [[ "$OPT_DELETE_NAMESPACE" == "true" ]] && what="$what; DELETE NAMESPACE"
  [[ "$OPT_UNINSTALL_PLATFORM" == "true" ]] && what="$what; uninstall platform components recorded in maop-installed-components"
  confirm_target "down ($what)"

  local kind sel="app.kubernetes.io/part-of=${MAOP_PART_OF}"
  for kind in $(_owned_kinds); do
    "$KC" delete "$kind" -n "$MAOP_NAMESPACE" -l "$MAOP_OWNED_SELECTOR" --ignore-not-found --wait=true 2>&1 \
      | grep -v -E "doesn't have a resource type|the server could not find the requested resource" >&2 || true
  done
  "$KC" delete clusterspiffeid "maop-${MAOP_NAMESPACE}" --ignore-not-found 2>/dev/null || true
  if [[ "$OPT_DELETE_DATA" == "true" ]]; then
    kn delete pvc -l "$sel" --ignore-not-found --wait=false
    kn delete secret -l "$sel" --ignore-not-found
  else
    info "kept: PVCs and Secrets (use --delete-data to remove them)"
  fi
  if [[ "$OPT_UNINSTALL_PLATFORM" == "true" ]]; then
    uninstall_recorded_platform
  fi
  if [[ "$OPT_DELETE_NAMESPACE" == "true" ]]; then
    if [[ "$OPT_UNINSTALL_PLATFORM" != "true" && -n "$(installed_components_get keda)$(installed_components_get spire)" ]]; then
      warn "this namespace holds the record of platform components installed by this project; after deleting it, 'down --uninstall-platform-components' can no longer find them (helm releases: ${KEDA_NAMESPACE}/${KEDA_RELEASE}, ${SPIRE_RELEASE_NAMESPACE}/${SPIRE_RELEASE})"
    fi
    log "deleting namespace $MAOP_NAMESPACE"
    "$KC" delete namespace "$MAOP_NAMESPACE" --wait=false
  fi
  log "down complete"
}
