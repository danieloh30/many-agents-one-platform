# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# ./demo.sh up — idempotent deploy.

# ensure_secret <name> <key> <generator> [<key> <generator> ...]
# Creates the Secret when absent; when present, adds only missing keys. Never overwrites a value,
# never prints one. Values travel through files in a private temp dir, not command arguments.
ensure_secret() {
  local name="$1"; shift
  local dir existing="" key gen missing=() args=()
  dir="$(maop_tmpdir)/secret-${name}"; (umask 077; mkdir -p "$dir")
  if "$KC" get secret "$name" -n "$MAOP_NAMESPACE" >/dev/null 2>&1; then
    existing="$("$KC" get secret "$name" -n "$MAOP_NAMESPACE" -o json | jq -r '.data // {} | keys[]')"
  fi
  while [[ $# -gt 0 ]]; do
    key="$1"; gen="$2"; shift 2
    if ! grep -qx -- "$key" <<< "$existing"; then
      (umask 077; "$gen" > "$dir/$key")
      missing+=("$key")
      args+=("--from-file=${key}=${dir}/${key}")
    fi
  done
  if ((${#missing[@]} == 0)); then
    info "secret $name: present (kept)"
  elif [[ -z "$existing" ]]; then
    "$KC" create secret generic "$name" -n "$MAOP_NAMESPACE" "${args[@]}" --dry-run=client -o json \
      | jq --arg mb "$MAOP_MANAGED_BY" --arg po "$MAOP_PART_OF" --arg n "$name" \
          '.metadata.labels = {"app.kubernetes.io/name": $n, "app.kubernetes.io/part-of": $po, "app.kubernetes.io/managed-by": $mb}' \
      | "$KC" apply -f - >/dev/null
    info "secret $name: created"
  else
    local patch="$dir/patch.json"
    "$KC" create secret generic "$name" -n "$MAOP_NAMESPACE" "${args[@]}" --dry-run=client -o json \
      | jq '{data: .data}' > "$patch"
    "$KC" patch secret "$name" -n "$MAOP_NAMESPACE" --type merge --patch-file "$patch" >/dev/null
    info "secret $name: added missing keys (${missing[*]})"
  fi
  rm -rf "$dir"
}

gen_password()       { rand_hex 24; }
gen_nats_password()  { printf 'n%s' "$(rand_hex 24)"; }     # letter first: NATS config reads it as a string
gen_presenter_token(){ rand_hex 32; }                        # 256 bit (docs/security.md §6)

ensure_secrets() {
  log "secrets (generated once, never overwritten)"
  ensure_secret postgres-admin admin-password gen_password owner-password gen_password
  ensure_secret postgres-roles triage-password gen_password tools-password gen_password
  ensure_secret nats-auth \
    triage-agent gen_nats_password evidence-worker gen_nats_password \
    recommendation-agent gen_nats_password tool-service gen_nats_password ops-console gen_nats_password
  ensure_secret ops-console-presenter token gen_presenter_token
  ensure_secret grafana-admin admin-password gen_password

  # Model credentials: refreshed from .env.local on every up (the only secret that is replaced).
  local dir args=()
  dir="$(maop_tmpdir)/secret-model"; (umask 077; mkdir -p "$dir")
  if [[ -n "${MAOP_MODEL_API_KEY:-}" ]]; then
    (umask 077; printf '%s' "$MAOP_MODEL_API_KEY" > "$dir/key")
    args+=("--from-file=MAOP_MODEL_API_KEY=${dir}/key")
  fi
  "$KC" create secret generic model-credentials -n "$MAOP_NAMESPACE" ${args[@]+"${args[@]}"} --dry-run=client -o json \
    | jq --arg mb "$MAOP_MANAGED_BY" --arg po "$MAOP_PART_OF" \
        '.metadata.labels = {"app.kubernetes.io/name": "model-credentials", "app.kubernetes.io/part-of": $po, "app.kubernetes.io/managed-by": $mb}' \
    | "$KC" apply -f - >/dev/null
  rm -rf "$dir"
  if [[ -n "${MAOP_MODEL_API_KEY:-}" ]]; then info "secret model-credentials: refreshed from .env.local"
  else info "secret model-credentials: no MAOP_MODEL_API_KEY set (empty secret)"; fi
}

# External URLs of the exposed UIs (empty when not exposed / not found).
route_url() {
  local name="$1" host=""
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    host="$("$KC" get route "$name" -n "$MAOP_NAMESPACE" -o jsonpath='{.spec.host}' 2>/dev/null || true)"
    [[ -n "$host" ]] && printf 'https://%s\n' "$host"
  else
    host="$("$KC" get ingress "$name" -n "$MAOP_NAMESPACE" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || true)"
    [[ -n "$host" ]] && printf '%s://%s\n' "$MAOP_INGRESS_SCHEME" "$host"
  fi
  return 0
}

current_console_config() {
  "$KC" get configmap ops-console-config -n "$MAOP_NAMESPACE" -o jsonpath="{.data.$1}" 2>/dev/null || true
}

wait_rollouts() {
  log "waiting for StatefulSets"
  kn rollout status statefulset/postgres --timeout=10m
  kn rollout status statefulset/nats --timeout=10m
  log "waiting for Job postgres-setup"
  kn wait --for=condition=complete job/postgres-setup --timeout=10m \
    || { kn logs job/postgres-setup --tail=50 >&2 || true; die "postgres-setup did not complete"; }
  log "waiting for Deployments"
  local d
  for d in otel-collector jaeger prometheus grafana tool-service triage-agent evidence-worker recommendation-agent ops-console; do
    kn rollout status "deployment/$d" --timeout=10m || die "deployment $d did not become ready (try: ./demo.sh logs $d)"
  done
}

print_access() {
  local console jaeger grafana
  console="$(route_url ops-console)"; jaeger="$(route_url jaeger)"; grafana="$(route_url grafana)"
  printf '\n%sThe demo is up.%s\n' "$C_GRN" "$C_RST"
  printf '  Console:  %s\n' "${console:-<not exposed>}"
  printf '  Jaeger:   %s\n' "${jaeger:-<not exposed>}"
  printf '  Grafana:  %s  (anonymous Viewer)\n' "${grafana:-<not exposed>}"
  printf '  Presenter token: ./demo.sh token   (printed only on request)\n'
  printf '  Evidence:        ./demo.sh verify\n\n'
}

cmd_up() {
  log "preflight (doctor)"
  run_doctor || die "doctor reported FAIL; fix the items above (doctor is read-only), then rerun"
  # run_doctor already computed the platform plan (plan_platform).
  [[ "$PLAN_KEDA_ACTION" != "blocked" && "$PLAN_SPIRE_ACTION" != "blocked" ]] || die "platform prerequisites missing: ${PLAN_PROBLEMS}"
  if [[ "$OPT_SKIP_BUILD" != "true" && "$MAOP_PLATFORM" == "kubernetes" && -z "$MAOP_REGISTRY" ]]; then
    die "MAOP_REGISTRY is required to build for --platform kubernetes"
  fi

  local cluster_changes="namespace, secrets, application manifests"
  [[ "$PLAN_KEDA_ACTION" == "install" ]] && cluster_changes="$cluster_changes, INSTALL KEDA (cluster-scoped)"
  [[ "$PLAN_SPIRE_ACTION" == "install" ]] && cluster_changes="$cluster_changes, INSTALL SPIRE (cluster-scoped, privileged SCCs)"
  confirm_target "up ($cluster_changes)"

  ensure_namespace
  ensure_platform
  ensure_secrets

  if [[ "$OPT_SKIP_BUILD" == "true" ]]; then
    load_image_record || die "--skip-build: no image record for this platform/namespace; run ./demo.sh build first"
    log "using the images from the last build ($(image_record_file | sed "s#${REPO_ROOT}/##"))"
  else
    do_build
  fi

  R_SCALER="$PLAN_SCALER"
  R_TRUST_DOMAIN="$PLAN_TRUST_DOMAIN"
  R_CLASS_NAME="$PLAN_CLASS_NAME"
  R_JAEGER_URL="$(current_console_config MAOP_JAEGER_URL)"
  R_GRAFANA_URL="$(current_console_config MAOP_GRAFANA_URL)"
  local rendered
  rendered="${MAOP_STATE_DIR}/render/${MAOP_NAMESPACE}.yaml"
  render_manifests "$rendered"
  log "rendered manifests: ${rendered#"$REPO_ROOT"/} (overlay $(render_overlay_name), trust domain ${R_TRUST_DOMAIN})"

  # Job templates are immutable: delete and recreate so the setup reruns idempotently.
  kn delete job postgres-setup --ignore-not-found --wait=true >/dev/null
  log "applying manifests (server-side apply)"
  "$KC" apply --server-side --force-conflicts --field-manager=maop-demo -f "$rendered"
  if [[ "${MAOP_NETWORK_POLICIES:-true}" == "false" ]]; then
    # Server-side apply never deletes: remove this project's policies explicitly.
    "$KC" delete networkpolicy -n "$MAOP_NAMESPACE" -l app.kubernetes.io/part-of=maop --ignore-not-found >/dev/null
  fi

  wait_rollouts

  # Console links: discover external hosts, write them into ops-console-config, restart if changed.
  local jaeger grafana
  jaeger=""; grafana=""
  if [[ "$MAOP_EXPOSE_OBSERVABILITY" == "true" ]]; then
    jaeger="$(route_url jaeger)"; grafana="$(route_url grafana)"
  fi
  if [[ "$jaeger" != "$R_JAEGER_URL" || "$grafana" != "$R_GRAFANA_URL" ]]; then
    log "writing external Jaeger/Grafana URLs into ops-console-config and restarting ops-console"
    kn patch configmap ops-console-config --type merge \
      -p "$(jq -n --arg j "$jaeger" --arg g "$grafana" '{data: {MAOP_JAEGER_URL: $j, MAOP_GRAFANA_URL: $g}}')" >/dev/null
    kn rollout restart deployment/ops-console >/dev/null
    kn rollout status deployment/ops-console --timeout=5m
  fi

  log "smoke test (verify --quick)"
  OPT_QUICK=true
  if ! cmd_verify; then
    print_access
    die "smoke test failed; evidence is in the artifacts directory printed above"
  fi
  print_access
}
