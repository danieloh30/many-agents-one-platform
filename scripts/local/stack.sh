#!/usr/bin/env bash
# Local full stack for development and end-to-end checks without a cluster.
# Infrastructure (PostgreSQL, NATS, OPA, Jaeger, Prometheus, Grafana) runs in containers (podman or docker);
# the five Java services run as local JVMs.
#
#   scripts/local/stack.sh up      # build is not done here: run ./mvnw -B package -DskipTests first
#   scripts/local/stack.sh e2e     # run the scenario checks against the local console API
#   scripts/local/stack.sh down
#
# Identity: a local CA issues X.509-SVID-shaped certificates (single spiffe:// URI SAN) into
# .maop/local/pki/<workload>/ in the spiffe-helper file layout. The mTLS code path is the same as in
# the cluster; only the issuer differs, and the console labels identity mode DEV_STATIC.
# Model: deterministic by default here (no credentials needed); set MAOP_MODEL_MODE=live and the
# MAOP_MODEL_* variables to use a real endpoint.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.maop/local"
PKI="$STATE/pki"
LOGS="$STATE/logs"
ENGINE="${MAOP_CONTAINER_ENGINE:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}"
TD="maop.local"
NET="maop-local"
PG_IMAGE="docker.io/library/postgres:18.1"
NATS_IMAGE="docker.io/library/nats:2.15.3-alpine"
OPA_IMAGE="docker.io/openpolicyagent/opa:1.22.4-static"
JAEGER_IMAGE="docker.io/jaegertracing/jaeger:2.23.0"
PROMETHEUS_IMAGE="docker.io/prom/prometheus:v3.16.0"
GRAFANA_IMAGE="docker.io/grafana/grafana:13.3.1"
TOKEN_FILE="$STATE/presenter-token"
CONSOLE="http://localhost:8080"

log() { printf '\033[1;36m[local]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[local]\033[0m %s\n' "$*" >&2; exit 1; }

issue() { # workload
  local w="$1" dir="$PKI/$1"
  mkdir -p "$dir"
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$dir/svid_key.pem" 2>/dev/null
  openssl req -new -key "$dir/svid_key.pem" -subj "/O=SPIRE-local" -out "$dir/req.csr" 2>/dev/null
  printf 'subjectAltName=URI:spiffe://%s/ns/maop/sa/%s,DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyAgreement\nextendedKeyUsage=serverAuth,clientAuth\n' "$TD" "$w" > "$dir/ext.cnf"
  openssl x509 -req -in "$dir/req.csr" -CA "$PKI/ca.pem" -CAkey "$PKI/ca_key.pem" -CAcreateserial \
    -days 2 -sha256 -extfile "$dir/ext.cnf" -out "$dir/svid.pem" 2>/dev/null
  cp "$PKI/ca.pem" "$dir/svid_bundle.pem"
  rm -f "$dir/req.csr" "$dir/ext.cnf"
}

certs() {
  mkdir -p "$PKI"
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$PKI/ca_key.pem" 2>/dev/null
  openssl req -x509 -new -key "$PKI/ca_key.pem" -subj "/CN=maop local trust domain CA" -days 2 -sha256 \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$PKI/ca.pem" 2>/dev/null
  for w in tool-service triage-agent evidence-worker recommendation-agent ops-console; do issue "$w"; done
  log "issued local SVIDs for trust domain $TD"
}

infra_up() {
  "$ENGINE" network exists "$NET" 2>/dev/null || "$ENGINE" network create "$NET" >/dev/null
  run() { local name="$1"; shift; "$ENGINE" rm -f "$name" >/dev/null 2>&1 || true; "$ENGINE" run -d --name "$name" --network "$NET" "$@" >/dev/null; }
  run maop-postgres -p 5432:5432 -e POSTGRES_USER=maop -e POSTGRES_PASSWORD=maop -e POSTGRES_DB=maop "$PG_IMAGE"
  run maop-nats -p 4222:4222 -p 8222:8222 "$NATS_IMAGE" -js -m 8222
  run maop-opa -p 8181:8181 -v "$ROOT/policy/maop:/policy/maop:ro,Z" "$OPA_IMAGE" run --server --addr=0.0.0.0:8181 \
    --set=decision_logs.console=true --ignore='*_test.rego' /policy
  run maop-jaeger -p 16686:16686 -p 4317:4317 -p 4318:4318 "$JAEGER_IMAGE"
  # Prometheus and Grafana with the same images, datasources and dashboards as the cluster deployment.
  local host_alias; host_alias="$([ "$(basename "$ENGINE")" = podman ] && echo host.containers.internal || echo host.docker.internal)"
  mkdir -p "$STATE/observability"
  sed "s/HOST_ALIAS/$host_alias/g" "$ROOT/scripts/local/observability/prometheus.yml.tmpl" > "$STATE/observability/prometheus.yml"
  run maop-prometheus -p 9090:9090 \
    -v "$STATE/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro,Z" "$PROMETHEUS_IMAGE" \
    --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.retention.time=6h --web.enable-lifecycle
  run maop-grafana -p 3000:3000 \
    -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer -e GF_USERS_ALLOW_SIGN_UP=false \
    -e GF_ANALYTICS_REPORTING_ENABLED=false -e GF_ANALYTICS_CHECK_FOR_UPDATES=false -e GF_PLUGINS_PREINSTALL_DISABLED=true \
    -e GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/var/lib/grafana-dashboards/maop-overview.json \
    -v "$ROOT/scripts/local/observability/datasources.yaml:/etc/grafana/provisioning/datasources/maop.yaml:ro,Z" \
    -v "$ROOT/scripts/local/observability/dashboards.yaml:/etc/grafana/provisioning/dashboards/maop.yaml:ro,Z" \
    -v "$ROOT/deploy/base/observability/grafana/dashboards:/var/lib/grafana-dashboards:ro,Z" "$GRAFANA_IMAGE"
  log "waiting for PostgreSQL"
  for _ in $(seq 1 60); do "$ENGINE" exec maop-postgres pg_isready -U maop >/dev/null 2>&1 && break; sleep 1; done
}

svc() { # name jar-dir env...
  local name="$1" dir="$2"; shift 2
  [ -f "$ROOT/$dir/target/quarkus-app/quarkus-run.jar" ] || die "$dir is not built: run ./mvnw -B package -DskipTests"
  env "$@" MAOP_TRUST_DOMAIN="$TD" MAOP_SPIFFE_DIR="$PKI/$name" MAOP_NATS_URL=nats://localhost:4222 \
    MAOP_TOOL_SERVICE_URL=https://localhost:8443 MAOP_TOOL_SERVICE_SPIFFE_ID="spiffe://$TD/ns/maop/sa/tool-service" \
    QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 MAOP_MODEL_MODE="${MAOP_MODEL_MODE:-deterministic}" \
    nohup java -jar "$ROOT/$dir/target/quarkus-app/quarkus-run.jar" > "$LOGS/$name.log" 2>&1 &
  echo $! > "$STATE/$name.pid"
}

up() {
  mkdir -p "$STATE" "$LOGS"
  [ -f "$PKI/ca.pem" ] || certs
  [ -s "$TOKEN_FILE" ] || (umask 077; openssl rand -hex 24 > "$TOKEN_FILE")
  infra_up
  local db="QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/maop QUARKUS_DATASOURCE_USERNAME=maop QUARKUS_DATASOURCE_PASSWORD=maop"
  # shellcheck disable=SC2086
  svc tool-service tool-service $db MAOP_OPA_URL=http://localhost:8181 QUARKUS_MANAGEMENT_PORT=9001
  # shellcheck disable=SC2086
  svc triage-agent triage-agent $db QUARKUS_HTTP_PORT=8081 QUARKUS_MANAGEMENT_PORT=9002
  svc evidence-worker evidence-worker QUARKUS_HTTP_PORT=8082 QUARKUS_MANAGEMENT_PORT=9003 HOSTNAME=evidence-worker-local-1
  svc recommendation-agent recommendation-agent QUARKUS_HTTP_PORT=8083 QUARKUS_MANAGEMENT_PORT=9004 HOSTNAME=recommendation-agent-local-1
  svc ops-console ops-console QUARKUS_HTTP_PORT=8080 QUARKUS_MANAGEMENT_PORT=9005 MAOP_TRIAGE_URL=http://localhost:8081 \
    MAOP_PRESENTER_TOKEN="$(cat "$TOKEN_FILE")" MAOP_IDENTITY_MODE=DEV_STATIC MAOP_JAEGER_URL=http://localhost:16686 \
    MAOP_JAEGER_INTERNAL_URL=http://localhost:16686 MAOP_PROMETHEUS_URL=http://localhost:9090 \
    MAOP_GRAFANA_URL=http://localhost:3000 MAOP_GRAFANA_INTERNAL_URL=http://localhost:3000
  for p in 9001 9002 9003 9004 9005; do
    for _ in $(seq 1 90); do curl -fsS "http://localhost:$p/q/health/ready" >/dev/null 2>&1 && break; sleep 1; done
    curl -fsS "http://localhost:$p/q/health/ready" >/dev/null || die "service on management port $p not ready (see $LOGS)"
  done
  log "stack ready: console $CONSOLE  jaeger http://localhost:16686  grafana http://localhost:3000  prometheus http://localhost:9090"
  log "presenter token is in $TOKEN_FILE (not printed)"
}

down() {
  for f in "$STATE"/*.pid; do
    if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
  for c in maop-postgres maop-nats maop-opa maop-jaeger maop-prometheus maop-grafana; do "$ENGINE" rm -f "$c" >/dev/null 2>&1 || true; done
  log "stopped"
}

api() { # method path [body]
  local auth=(-H "Authorization: Bearer $(cat "$TOKEN_FILE")")
  if [ $# -ge 3 ]; then
    curl -sS -X "$1" "${auth[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: local-$(date +%s%N)-$RANDOM" -d "$3" "$CONSOLE$2"
  else
    curl -sS -X "$1" "${auth[@]}" "$CONSOLE$2"
  fi
}

await_state() { # runId state... -> prints final state
  local id="$1"; shift
  for _ in $(seq 1 90); do
    local s; s="$(api GET "/api/v1/runs/$id" | jq -r .state)"
    for want in "$@"; do [ "$s" = "$want" ] && { echo "$s"; return 0; }; done
    case "$s" in COMPLETED|NEEDS_REVIEW|TIMED_OUT|FAILED|CANCELLED) echo "$s"; return 1;; esac
    sleep 1
  done
  echo "timeout"; return 1
}

scenario_request() { api GET /api/v1/scenarios | jq -c --arg id "$1" '.items[] | select(.id == $id) | .request'; }

e2e() {
  local out fail=0
  out="$STATE/e2e-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$out"
  check() { if [ "$2" = "PASS" ]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; else printf '  \033[31mFAIL\033[0m %s (%s)\n' "$1" "${3:-}"; fail=1; fi; echo "$2 $1 ${3:-}" >> "$out/summary.txt"; }

  log "1. successful run"
  local id; id="$(api POST /api/v1/runs "$(scenario_request SUCCESS)" | jq -r .runId)"
  local st; st="$(await_state "$id" AWAITING_APPROVAL || true)"
  api GET "/api/v1/runs/$id" > "$out/success-run.json"
  if [ "$st" = "AWAITING_APPROVAL" ]; then check "success run reaches AWAITING_APPROVAL" PASS; else check "success run reaches AWAITING_APPROVAL" FAIL "$st"; fi
  local digest; digest="$(jq -r .recommendation.proposedAction.actionDigest "$out/success-run.json")"
  api POST "/api/v1/runs/$id/approval" "{\"decision\":\"APPROVE\",\"actionDigest\":\"$digest\"}" > /dev/null
  st="$(await_state "$id" COMPLETED || true)"
  if [ "$(api GET "/api/v1/runs/$id" | jq -r .outcome)" = "REMEDIATION_SIMULATED" ]; then check "approved simulated action completes" PASS; else check "approved simulated action completes" FAIL "$st"; fi
  local trace; trace="$(jq -r .traceId "$out/success-run.json")"
  sleep 5
  curl -sS "http://localhost:16686/api/traces/$trace" > "$out/trace.json" || true
  local services; services="$(jq -r '[.data[0].processes[].serviceName] | unique | join(",")' "$out/trace.json" 2>/dev/null || echo "")"
  local n; n="$(jq -r '[.data[0].processes[].serviceName] | unique | length' "$out/trace.json" 2>/dev/null || echo 0)"
  if [ "${n:-0}" -ge 5 ]; then check "one trace spans all five services ($services)" PASS; else check "connected trace" FAIL "services=$services"; fi

  log "2. unapproved remediation"
  id="$(api POST /api/v1/runs "$(scenario_request POLICY_DENIAL)" | jq -r .runId)"
  st="$(await_state "$id" AWAITING_APPROVAL || true)"
  api GET "/api/v1/audit/invocations?runId=$id" > "$out/denial-audit.json"
  local denied; denied="$(jq '[.items[] | select(.toolClass=="REMEDIATION" and .status=="DENIED" and (.decision.reasons | index("approval_required")) and .sideEffect==null)] | length' "$out/denial-audit.json")"
  if [ "$denied" -ge 1 ]; then check "remediation denied by policy before execution" PASS; else check "policy denial" FAIL "denied=$denied state=$st"; fi
  local ids; ids="$(jq -r '[.items[].caller.spiffeId] | unique | join(",")' "$out/denial-audit.json")"
  if [[ "$ids" == spiffe://$TD/ns/maop/sa/* ]]; then check "audit callers are verified SPIFFE IDs ($ids)" PASS; else check "verified identity" FAIL "$ids"; fi

  log "3. identity enforcement"
  if curl -sS -k --max-time 5 -H "X-Spiffe-Id: spiffe://$TD/ns/maop/sa/ops-console" https://localhost:8443/v1/tools >/dev/null 2>&1; then
    check "tool API refuses clients without an SVID" FAIL "request succeeded"
  else
    check "tool API refuses clients without an SVID (forged header ignored)" PASS
  fi

  log "4. redelivery without duplicate effect"
  id="$(api POST /api/v1/runs "$(scenario_request REDELIVERY)" | jq -r .runId)"
  st="$(await_state "$id" AWAITING_APPROVAL || true)"
  api GET "/api/v1/audit/invocations?runId=$id" > "$out/redelivery-audit.json"
  api GET "/api/v1/runs/$id" > "$out/redelivery-run.json"
  local dedup; dedup="$(jq '[.items[] | select(.requests >= 2 and .executions == 1)] | length' "$out/redelivery-audit.json")"
  local deliv; deliv="$(jq '[.tasks[] | select(.deliveries >= 2)] | length' "$out/redelivery-run.json")"
  if [ "$dedup" -ge 1 ] && [ "$deliv" -ge 1 ]; then check "redelivered task replayed its tool call (requests>=2, executions=1)" PASS; else check "redelivery dedup" FAIL "dedup=$dedup deliveries=$deliv state=$st"; fi

  log "5. bounded burst (queue depth only; no autoscaler locally)"
  api POST /api/v1/bursts '{"runs":12}' > "$out/burst.json"
  local burst; burst="$(jq -r .burstId "$out/burst.json")"
  local maxdepth=0 d
  for _ in $(seq 1 50); do
    d="$(api GET /api/v1/ops/snapshot | jq '.queues[] | select(.consumer=="evidence-workers") | ((.pending // 0) + (.inFlight // 0))')"
    if [ "${d:-0}" -gt "$maxdepth" ]; then maxdepth="$d"; fi
    sleep 0.05
  done
  for _ in $(seq 1 240); do
    [ "$(api GET '/api/v1/bursts/current' | jq -r .active)" = "false" ] && break
    sleep 1
  done
  local done_runs; done_runs="$(api GET '/api/v1/runs?limit=50' | jq --arg b "$burst" '[.items[] | select(.burstId==$b and .outcome=="EVIDENCE_ONLY")] | length')"
  if [ "$maxdepth" -gt 0 ]; then check "burst raised evidence queue depth (max observed pending+inFlight=$maxdepth)" PASS; else check "burst queue depth" FAIL "max=$maxdepth"; fi
  if [ "$done_runs" -eq 12 ]; then check "all 12 load runs drained to EVIDENCE_ONLY" PASS; else check "burst drained" FAIL "completed=$done_runs"; fi
  api POST /api/v1/bursts/stop > /dev/null
  log "evidence written to $out"
  return $fail
}

case "${1:-}" in
  up) up ;;
  down) down ;;
  certs) certs ;;
  e2e) e2e ;;
  *) echo "usage: $0 up|e2e|down|certs" >&2; exit 2 ;;
esac
