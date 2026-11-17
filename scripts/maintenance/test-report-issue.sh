#!/usr/bin/env bash
# Offline tests for report-issue.sh: which results keep the report open and which let it close.
set -o errexit -o nounset -o pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fails=0

clear_results() { # all checks up to date
  rm -rf "$work/out"; mkdir -p "$work/out"
  echo '{"patch_available":false,"newer_lts_available":false}' >"$work/out/quarkus-platform.json"
  echo '{"independent_libraries":[{"coordinates":"io.nats:jnats","pinned":"2.27.0","latest_stable":"2.27.0","status":"up to date"}]}' >"$work/out/extension-compat.json"
  echo '{"updates_available":false,"bump_level":"none"}' >"$work/out/charts.json"
  echo '{"update_available":false,"current":"3.9.16","latest_in_line":"3.9.16"}' >"$work/out/maven-wrapper.json"
  echo '{"nvmrc":"26","latest_lts":"26.13.1","update_available":false,"drift":[]}' >"$work/out/node-toolchain.json"
}
decide() { MAINT_OUT_DIR="$work/out" REPORT_DRY_RUN=true "$here/report-issue.sh" 2>/dev/null; }
check() { # description output expected-decision expected-text
  local got; got="$(sed -n 's/^decision: //p' <<<"$2")"
  if [[ "$got" == "$3" ]] && grep -qF -- "$4" <<<"$2"; then echo "ok   $1"
  else echo "FAIL $1: decision '$got' (expected '$3'), text '$4' $(grep -qF -- "$4" <<<"$2" && echo found || echo missing)"; fails=$((fails + 1)); fi
}

clear_results
check "all up to date: close" "$(decide)" close "**All clear:**"

clear_results
echo '{"independent_libraries":[{"coordinates":"io.nats:jnats","pinned":"2.26.4","latest_stable":"2.27.0","status":"update available (minor)"}]}' >"$work/out/extension-compat.json"
check "library update: stay open" "$(decide)" open "- io.nats:jnats 2.26.4 -> 2.27.0"

clear_results
echo '{"updates_available":true,"bump_level":"patch"}' >"$work/out/charts.json"
check "chart update: stay open" "$(decide)" open "- Helm chart pins: patch update available"

clear_results
echo '{"nvmrc":"26","latest_lts":"26.13.1","update_available":false,"drift":["engines.node differs"]}' >"$work/out/node-toolchain.json"
check "toolchain drift: stay open" "$(decide)" open "- Node.js toolchain drift: engines.node differs"

clear_results
echo '{"patch_available":false,"newer_lts_available":true,"newest_lts_stream":"3.45","newest_lts_version":"3.45.0"}' >"$work/out/quarkus-platform.json"
check "new Quarkus LTS: stay open" "$(decide)" open "- Quarkus LTS stream 3.45 (3.45.0)"

clear_results
rm "$work/out/charts.json"
check "a check without a result never closes the report" "$(decide)" open "- charts: the check produced no result"

if (( fails > 0 )); then echo "$fails failure(s)"; exit 1; fi
echo "all report-issue tests passed"
