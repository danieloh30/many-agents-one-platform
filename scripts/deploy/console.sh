# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# Access to the console API and Jaeger query API: through the external Route/Ingress when it
# answers, otherwise through `port-forward`. The presenter token is read from the Secret into a
# header file (mode 600) and passed to curl with -H @file — it is never printed or put on a
# command line.
#
# These functions set globals and must run in the current shell (not inside $(...)), so that
# port-forward processes are registered for cleanup.

CONSOLE_BASE=""; JAEGER_BASE=""; AUTH_HEADER_FILE=""; PF_URL=""; API_CODE=""

_curl() {
  if [[ "$MAOP_INSECURE_TLS" == "true" ]]; then curl -sS --max-time 30 -k "$@"; else curl -sS --max-time 30 "$@"; fi
}

# start_port_forward <service> <remote-port> <local-port>; sets PF_URL.
start_port_forward() {
  local svc="$1" rport="$2" lport="$3" logf i
  PF_URL=""
  logf="${MAOP_TMP}/pf-${svc}.log"
  "$KC" port-forward -n "$MAOP_NAMESPACE" "svc/${svc}" "${lport}:${rport}" > "$logf" 2>&1 &
  MAOP_CLEANUP_PIDS="$MAOP_CLEANUP_PIDS $!"
  for ((i = 0; i < 30; i++)); do
    if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${lport}/"; then
      PF_URL="http://127.0.0.1:${lport}"; return 0
    fi
    sleep 1
  done
  err "port-forward to svc/${svc} did not become ready: $(tail -n 3 "$logf" 2>/dev/null)"
  return 1
}

resolve_console() {
  [[ -n "$CONSOLE_BASE" ]] && return 0
  local url code
  url="$(route_url ops-console)"
  if [[ -n "$url" ]]; then
    code="$(_curl -o /dev/null -w '%{http_code}' "${url}/api/v1/config" 2>/dev/null || true)"
    if [[ "$code" == "200" ]]; then CONSOLE_BASE="$url"; return 0; fi
    warn "console at ${url} answered '${code:-no response}' (set MAOP_INSECURE_TLS=true for self-signed router certificates); using port-forward"
  fi
  start_port_forward ops-console 8080 18080 || die "console API not reachable"
  CONSOLE_BASE="$PF_URL"
}

resolve_jaeger() {
  [[ -n "$JAEGER_BASE" ]] && return 0
  local url code
  url="$(route_url jaeger)"
  if [[ -n "$url" ]]; then
    code="$(_curl -o /dev/null -w '%{http_code}' "${url}/api/services" 2>/dev/null || true)"
    if [[ "$code" == "200" ]]; then JAEGER_BASE="$url"; return 0; fi
  fi
  start_port_forward jaeger 16686 16686 || return 1
  JAEGER_BASE="$PF_URL"
}

load_presenter_token() {
  [[ -n "$AUTH_HEADER_FILE" ]] && return 0
  local token
  token="$("$KC" get secret ops-console-presenter -n "$MAOP_NAMESPACE" -o jsonpath='{.data.token}' 2>/dev/null | base64 -d 2>/dev/null || true)"
  [[ -n "$token" ]] || die "cannot read the presenter token from Secret ops-console-presenter"
  AUTH_HEADER_FILE="${MAOP_TMP}/presenter-header"
  (umask 077; printf 'Authorization: Bearer %s\n' "$token" > "$AUTH_HEADER_FILE")
  token=""
}

# console_api <METHOD> <path> <out-file> [<body-file>|-] [auth]
# Writes the response body to <out-file>; sets API_CODE (000 = no response).
console_api() {
  local method="$1" path="$2" out="$3" body="${4:-}" auth="${5:-}" code args=()
  resolve_console
  args=(-X "$method" -o "$out" -w '%{http_code}' -H 'Accept: application/json')
  if [[ -n "$body" && "$body" != "-" ]]; then args+=(-H 'Content-Type: application/json' --data-binary "@${body}"); fi
  if [[ "$auth" == "auth" ]]; then load_presenter_token; args+=(-H "@${AUTH_HEADER_FILE}"); fi
  if [[ "$method" == "POST" && "$path" == "/api/v1/runs" ]]; then args+=(-H "Idempotency-Key: $(new_uuid)"); fi
  : > "$out"
  code="$(_curl "${args[@]}" "${CONSOLE_BASE}${path}" 2>/dev/null || true)"
  API_CODE="${code:-000}"
  return 0
}
