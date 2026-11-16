# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# ./demo.sh verify — collects real evidence for each demo claim into artifacts/verify-<ts>/ and
# reports PASS/FAIL per check. Nothing is inferred: a check passes only on the observed data saved
# next to the summary. --quick (used by `up` as the smoke test) runs checks 1–4.

V_NAME=(); V_STATUS=(); V_DETAIL=()
v_record() { V_NAME+=("$1"); V_STATUS+=("$2"); V_DETAIL+=("$3"); printf '%s %-5s%s %s — %s\n' "$( [[ "$2" == PASS ]] && printf '%s' "$C_GRN" || printf '%s' "$C_RED")" "$2" "$C_RST" "$1" "$3" >&2; }

V_TERMINAL="COMPLETED NEEDS_REVIEW TIMED_OUT FAILED CANCELLED"

# submit_run <scenario> <remediationMode> <faultInjection> <out-file>; sets V_RUN_ID
submit_run() {
  local scenario="$1" mode="$2" fault="$3" out="$4" body
  body="${ART}/request-${scenario}.json"
  jq -n --arg s "$scenario" --arg m "$mode" --arg f "$fault" '{
      title: "Checkout error rate elevated after release",
      description: "5xx on POST /checkout rose from 0.2% to 7% starting 14:05 UTC.",
      service: "checkout", scenario: $s, remediationMode: $m, faultInjection: $f,
      labels: {source: "demo-verify"}}' > "$body"
  console_api POST /api/v1/runs "$out" "$body" auth
  V_RUN_ID="$(jq -r '.runId // empty' "$out" 2>/dev/null || true)"
  if [[ "$API_CODE" != 2* || -z "$V_RUN_ID" ]]; then
    V_RUN_ID=""
    return 1
  fi
}

# wait_run <runId> <out-file> <timeout-seconds>; sets V_RUN_STATE. Polls every 3 s.
wait_run() {
  local id="$1" out="$2" timeout="$3" start now state=""
  start="$(date +%s)"
  while :; do
    console_api GET "/api/v1/runs/${id}" "$out"
    state="$(jq -r '.state // empty' "$out" 2>/dev/null || true)"
    if [[ "$state" == "AWAITING_APPROVAL" ]] || [[ " $V_TERMINAL " == *" ${state} "* && -n "$state" ]]; then break; fi
    now="$(date +%s)"
    if ((now - start >= timeout)); then state="${state:-UNKNOWN}(timeout ${timeout}s)"; break; fi
    sleep 3
  done
  V_RUN_STATE="$state"
  V_RUN_SECONDS="$(( $(date +%s) - start ))"
}

fetch_audit() {  # fetch_audit <runId> <out-file>
  console_api GET "/api/v1/audit/invocations?runId=$1" "$2"
}

check_success_run() {
  if ! submit_run SUCCESS PROPOSE_ONLY NONE "${ART}/01-success-submit.json"; then
    v_record "1 success run" FAIL "submit returned HTTP ${API_CODE} (see 01-success-submit.json)"; return 0
  fi
  RUN_IDS="$RUN_IDS $V_RUN_ID"
  wait_run "$V_RUN_ID" "${ART}/01-success-run.json" 180
  SUCCESS_TRACE_ID="$(jq -r '.traceId // empty' "${ART}/01-success-run.json" 2>/dev/null || true)"
  if [[ "$V_RUN_STATE" == "AWAITING_APPROVAL" ]]; then
    v_record "1 success run" PASS "run ${V_RUN_ID} reached AWAITING_APPROVAL in ${V_RUN_SECONDS}s (model mode $(jq -r '.model.mode // "?"' "${ART}/01-success-run.json"))"
  else
    v_record "1 success run" FAIL "run ${V_RUN_ID} ended in ${V_RUN_STATE} after ${V_RUN_SECONDS}s, outcome $(jq -r '.outcome // "null"' "${ART}/01-success-run.json")"
  fi
}

check_trace() {
  if [[ -z "${SUCCESS_TRACE_ID:-}" ]]; then v_record "2 connected trace" FAIL "no traceId on the success run"; return 0; fi
  if ! resolve_jaeger; then v_record "2 connected trace" FAIL "Jaeger query API not reachable"; return 0; fi
  local out="${ART}/02-trace.json" i code services count=0
  for ((i = 0; i < 12; i++)); do
    code="$(_curl -o "$out" -w '%{http_code}' "${JAEGER_BASE}/api/traces/${SUCCESS_TRACE_ID}" 2>/dev/null || true)"
    if [[ "$code" == "200" ]]; then
      count="$(jq '[.data[]?.processes[]?.serviceName] | unique | length' "$out" 2>/dev/null || echo 0)"
      ((count >= 4)) && break
    fi
    sleep 5
  done
  services="$(jq -r '[.data[]?.processes[]?.serviceName] | unique | join(", ")' "$out" 2>/dev/null || true)"
  printf '%s\n' "$services" > "${ART}/02-trace-services.txt"
  if ((count >= 4)); then
    v_record "2 connected trace" PASS "trace ${SUCCESS_TRACE_ID}: ${count} services (${services})"
  else
    v_record "2 connected trace" FAIL "trace ${SUCCESS_TRACE_ID}: HTTP ${code:-000}, ${count} services (${services:-none}); expected >= 4"
  fi
}

check_policy_denial() {
  if ! submit_run POLICY_DENIAL ATTEMPT_UNAPPROVED NONE "${ART}/03-denial-submit.json"; then
    v_record "3 policy denial" FAIL "submit returned HTTP ${API_CODE}"; return 0
  fi
  local id="$V_RUN_ID"; RUN_IDS="$RUN_IDS $id"
  wait_run "$id" "${ART}/03-denial-run.json" 180
  fetch_audit "$id" "${ART}/03-denial-audit.json"
  local denied
  denied="$(jq '[.items[]? | select(.status == "DENIED" and ((.toolName // "") | startswith("remediation."))
                 and ((.decision.reasons // []) | index("approval_required")) and (.sideEffect == null))] | length' \
            "${ART}/03-denial-audit.json" 2>/dev/null || echo 0)"
  if [[ "$API_CODE" == "200" ]] && ((denied >= 1)); then
    v_record "3 policy denial" PASS "run ${id}: ${denied} DENIED remediation.* invocation(s), reason approval_required, sideEffect null; run state ${V_RUN_STATE}"
  else
    v_record "3 policy denial" FAIL "run ${id} (${V_RUN_STATE}): audit HTTP ${API_CODE}, matching DENIED records: ${denied}"
  fi
}

check_identity() {
  local td ns="$MAOP_NAMESPACE" pod out="${ART}/04-identity-probe.txt" overrides rc=0
  td="$("$KC" get configmap -n "$ns" -l app.kubernetes.io/name=maop-config -o json \
        | jq -r '[.items[] | {t: .metadata.creationTimestamp, v: .data.MAOP_TRUST_DOMAIN}] | sort_by(.t) | last | .v // empty' 2>/dev/null || true)"
  [[ -n "$td" ]] || { v_record "4 identity enforcement" FAIL "cannot read MAOP_TRUST_DOMAIN from maop-config"; return 0; }
  pod="maop-identity-probe-$(rand_hex 3)"
  local sc='{"runAsNonRoot": true, "seccompProfile": {"type": "RuntimeDefault"}}'
  [[ "$MAOP_PLATFORM" == "kubernetes" ]] && sc='{"runAsNonRoot": true, "runAsUser": 10001, "seccompProfile": {"type": "RuntimeDefault"}}'
  overrides="$(jq -n --arg name "$pod" --arg img "$MAOP_IDENTITY_PROBE_IMAGE" --argjson psc "$sc" \
    --arg cmd "curl -sS -o /dev/null -w 'http_code=%{http_code}\\n' --max-time 10 -k -H 'X-Spiffe-Id: spiffe://${td}/ns/${ns}/sa/recommendation-agent' https://tool-service:8443/v1/tools; echo curl_exit=\$?" '{
      apiVersion: "v1",
      spec: {
        automountServiceAccountToken: false,
        securityContext: $psc,
        containers: [{
          name: $name, image: $img, command: ["sh", "-c", $cmd],
          resources: {requests: {cpu: "10m", memory: "16Mi"}, limits: {cpu: "100m", memory: "64Mi"}},
          securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
        }]
      }}')"
  "$KC" run "$pod" -n "$ns" --rm -i --quiet --restart=Never --image="$MAOP_IDENTITY_PROBE_IMAGE" \
    --labels="maop.io/identity-probe=true,app.kubernetes.io/name=maop-identity-probe" \
    --pod-running-timeout=3m --overrides="$overrides" > "$out" 2>&1 || rc=$?
  local http exitc
  http="$(sed -n 's/.*http_code=\([0-9]*\).*/\1/p' "$out" | tail -n1)"
  exitc="$(sed -n 's/.*curl_exit=\([0-9]*\).*/\1/p' "$out" | tail -n1)"
  local tls_ok=false tls_detail
  if [[ -z "$exitc" ]]; then
    tls_detail="probe pod did not report a result (kubectl run exit ${rc}; see 04-identity-probe.txt)"
  elif [[ "$exitc" == "6" || "$exitc" == "7" || "$exitc" == "28" ]]; then
    tls_detail="tool-service not reached (curl exit ${exitc}: DNS/connect/timeout) — identity not tested"
  elif [[ "$exitc" != "0" && "${http:-000}" == "000" ]]; then
    tls_ok=true; tls_detail="no client certificate + forged X-Spiffe-Id: TLS handshake rejected (curl exit ${exitc}, no HTTP response)"
  else
    tls_detail="connection without client certificate got HTTP ${http} (curl exit ${exitc}) — mTLS NOT enforced"
  fi

  # Every audit record of the runs created by this verify names a verified SPIFFE ID in this namespace.
  local id prefix="spiffe://${td}/ns/${ns}/sa/" total=0 bad=0 n b
  for id in $RUN_IDS; do
    fetch_audit "$id" "${ART}/04-audit-${id}.json"
    n="$(jq '[.items[]?] | length' "${ART}/04-audit-${id}.json" 2>/dev/null || echo 0)"
    b="$(jq --arg p "$prefix" '[.items[]? | select(((.caller.spiffeId // "") | startswith($p)) | not)] | length' "${ART}/04-audit-${id}.json" 2>/dev/null || echo 0)"
    total=$((total + n)); bad=$((bad + b))
  done
  local audit_ok=false audit_detail
  if ((total > 0 && bad == 0)); then audit_ok=true; audit_detail="${total} audit records, all callers ${prefix}<sa>"
  else audit_detail="${total} audit records, ${bad} without a ${prefix}* caller"; fi

  if [[ "$tls_ok" == "true" && "$audit_ok" == "true" ]]; then
    v_record "4 identity enforcement" PASS "${tls_detail}; ${audit_detail}"
  else
    v_record "4 identity enforcement" FAIL "${tls_detail}; ${audit_detail}"
  fi
}

check_scale_out() {
  local log="${ART}/05-scale-out.tsv" min max r ready start now i
  r="$(kn get deploy evidence-worker -o jsonpath='{.status.replicas}' 2>/dev/null || true)"; min="${r:-0}"; max="$min"
  printf 'utc\treplicas\treadyReplicas\n' > "$log"
  printf '%s\t%s\t%s\n' "$(utc_now)" "$min" "$(kn get deploy evidence-worker -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" >> "$log"
  printf '{}\n' > "${ART}/request-burst.json"
  console_api POST /api/v1/bursts "${ART}/05-burst-start.json" "${ART}/request-burst.json" auth
  if [[ "$API_CODE" != 2* ]]; then
    v_record "5 scale-out" FAIL "POST /api/v1/bursts returned HTTP ${API_CODE}"; return 0
  fi
  start="$(date +%s)"
  for ((i = 0; i < 36; i++)); do
    sleep 5
    r="$(kn get deploy evidence-worker -o jsonpath='{.status.replicas}' 2>/dev/null || true)"
    ready="$(kn get deploy evidence-worker -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
    printf '%s\t%s\t%s\n' "$(utc_now)" "${r:-?}" "${ready:-0}" >> "$log"
    [[ "$r" =~ ^[0-9]+$ ]] && ((r > max)) && max="$r"
  done
  now="$(date +%s)"
  kn get scaledobject evidence-worker -o json > "${ART}/05-scaledobject.json" 2>/dev/null || true
  kn get hpa -o json > "${ART}/05-hpa.json" 2>/dev/null || true
  console_api POST /api/v1/bursts/stop "${ART}/05-burst-stop.json" - auth
  if ((max > min)); then
    v_record "5 scale-out" PASS "evidence-worker replicas ${min} -> max ${max} within $((now - start))s (timeline in 05-scale-out.tsv); burst stopped (HTTP ${API_CODE})"
  else
    v_record "5 scale-out" FAIL "replicas stayed at ${min} (max ${max}) for $((now - start))s; burst stop HTTP ${API_CODE}"
  fi
}

check_dedup() {
  if ! submit_run REDELIVERY PROPOSE_ONLY DROP_ACK_ONCE "${ART}/06-redelivery-submit.json"; then
    v_record "6 redelivery dedup" FAIL "submit returned HTTP ${API_CODE}"; return 0
  fi
  local id="$V_RUN_ID"; RUN_IDS="$RUN_IDS $id"
  wait_run "$id" "${ART}/06-redelivery-run.json" 240
  fetch_audit "$id" "${ART}/06-redelivery-audit.json"
  local inv task
  inv="$(jq '[.items[]? | select((.requests // 0) >= 2 and (.executions // -1) == 1)] | length' "${ART}/06-redelivery-audit.json" 2>/dev/null || echo 0)"
  task="$(jq '[.tasks[]? | select((.deliveries // 0) >= 2)] | length' "${ART}/06-redelivery-run.json" 2>/dev/null || echo 0)"
  if ((inv >= 1 && task >= 1)); then
    v_record "6 redelivery dedup" PASS "run ${id} (${V_RUN_STATE}): ${task} task(s) with deliveries >= 2; ${inv} invocation(s) with requests >= 2 and executions = 1"
  else
    v_record "6 redelivery dedup" FAIL "run ${id} (${V_RUN_STATE}): tasks with deliveries>=2: ${task}; invocations with requests>=2 & executions=1: ${inv}"
  fi
}

cmd_verify() {
  require_owned_namespace
  local ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  ART="${REPO_ROOT}/artifacts/verify-${ts}"
  mkdir -p "$ART"
  V_NAME=(); V_STATUS=(); V_DETAIL=(); RUN_IDS=""; SUCCESS_TRACE_ID=""
  log "verify ($([[ "$OPT_QUICK" == "true" ]] && echo quick || echo full)) -> ${ART#"$REPO_ROOT"/}"
  resolve_console
  info "console API: ${CONSOLE_BASE}"
  local cfg="${ART}/00-config.json"
  console_api GET /api/v1/config "$cfg"
  {
    printf 'started: %s\ncontext: %s\napi: %s\nnamespace: %s\nplatform: %s\n' \
      "$(utc_now)" "$(kube_context_name)" "$(kube_server_url)" "$MAOP_NAMESPACE" "$MAOP_PLATFORM"
    printf 'images:\n'; kn get deploy -o jsonpath='{range .items[*]}  {.metadata.name}: {.spec.template.spec.containers[0].image}{"\n"}{end}'
  } > "${ART}/00-environment.txt" 2>/dev/null || true
  kn get pods -o wide > "${ART}/00-pods.txt" 2>&1 || true

  check_success_run
  check_trace
  check_policy_denial
  if [[ "$OPT_QUICK" != "true" ]]; then check_dedup; fi
  check_identity
  if [[ "$OPT_QUICK" != "true" ]]; then check_scale_out; fi

  local i fails=0
  {
    printf 'verify %s — %s\n\n' "$ts" "$([[ "$OPT_QUICK" == "true" ]] && echo quick || echo full)"
    for ((i = 0; i < ${#V_NAME[@]}; i++)); do
      printf '%-5s %-24s %s\n' "${V_STATUS[i]}" "${V_NAME[i]}" "${V_DETAIL[i]}"
      [[ "${V_STATUS[i]}" == PASS ]] || fails=$((fails + 1))
    done
  } > "${ART}/summary.txt"
  jq -n --arg ts "$ts" \
    --argjson names "$(printf '%s\n' "${V_NAME[@]}" | jq -R . | jq -s .)" \
    --argjson st "$(printf '%s\n' "${V_STATUS[@]}" | jq -R . | jq -s .)" \
    --argjson de "$(printf '%s\n' "${V_DETAIL[@]}" | jq -R . | jq -s .)" \
    '{timestamp: $ts, checks: [range(0; $names|length) as $i | {check: $names[$i], result: $st[$i], detail: $de[$i]}]}' \
    > "${ART}/summary.json"
  printf '\n'; cat "${ART}/summary.txt"; printf '\nEvidence: %s\n' "${ART#"$REPO_ROOT"/}"
  ((fails == 0))
}
