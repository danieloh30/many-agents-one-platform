#!/usr/bin/env bash
# Table-driven dry-run tests for classify-update.sh (backlog B-35).
set -o errexit -o nounset -o pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pass=0 failc=0

# name | expected | ecosystem | update-type | deps-json
run_case() {
  local name="$1" expected="$2" eco="$3" utype="$4" deps="$5" out got
  out="$(env -u GITHUB_OUTPUT PACKAGE_ECOSYSTEM="$eco" UPDATE_TYPE="$utype" UPDATED_DEPS_JSON="$deps" \
    DEPENDENCY_NAMES="" PREVIOUS_VERSION="" NEW_VERSION="" MAINT_OUT_DIR="${TMPDIR:-/tmp}/maop-classify-test" \
    bash "$here/classify-update.sh")"
  got="$(sed -n 's/^eligible=//p' <<<"$out")"
  if [[ "$got" == "$expected" ]]; then
    pass=$((pass + 1)); printf 'ok   %-45s eligible=%s (%s)\n' "$name" "$got" "$(sed -n 's/^reason=//p' <<<"$out")"
  else
    failc=$((failc + 1)); printf 'FAIL %-45s expected %s, got %s: %s\n' "$name" "$expected" "$got" "$out"
  fi
}
dep() { printf '[{"dependencyName":"%s","prevVersion":"%s","newVersion":"%s","updateType":"%s"}]' "$1" "$2" "$3" "$4"; }

P=version-update:semver-patch M=version-update:semver-minor X=version-update:semver-major
run_case "maven patch"                          true  maven "$P" "$(dep io.nats:jnats 2.26.4 2.26.5 "$P")"
run_case "maven minor"                          true  maven "$M" "$(dep io.cloudevents:cloudevents-core 5.1.0 5.2.0 "$M")"
run_case "maven major"                          false maven "$X" "$(dep io.cloudevents:cloudevents-core 5.1.0 6.0.0 "$X")"
run_case "quarkus same stream (4-part)"         true  maven "$P" "$(dep io.quarkus.platform:quarkus-bom 3.40.1.2 3.40.1.3 "$P")"
run_case "quarkus same stream, no update-type"  true  maven ""   "$(dep io.quarkus.platform:quarkus-bom 3.40.1.2 3.40.2 "")"
run_case "quarkus stream change mislabelled"    false maven "$P" "$(dep io.quarkus.platform:quarkus-bom 3.40.1.2 3.47.0 "$P")"
run_case "quarkus major"                        false maven "$X" "$(dep io.quarkus.platform:quarkus-bom 3.40.1.2 4.0.0 "$X")"
run_case "group with one major"                 false npm_and_yarn "$X" '[{"dependencyName":"vite","prevVersion":"8.3.8","newVersion":"8.3.9","updateType":"version-update:semver-patch"},{"dependencyName":"zod","prevVersion":"4.6.7","newVersion":"5.0.0","updateType":"version-update:semver-major"}]'
run_case "group all minor/patch"                true  npm_and_yarn "$M" '[{"dependencyName":"vite","prevVersion":"8.3.8","newVersion":"8.3.9","updateType":"version-update:semver-patch"},{"dependencyName":"zod","prevVersion":"4.6.7","newVersion":"4.7.0","updateType":"version-update:semver-minor"}]'
run_case "actions minor"                        true  github_actions "$M" "$(dep actions/checkout v7.0.1 v7.1.0 "$M")"
run_case "docker patch with suffix"             true  docker "$P" "$(dep docker.io/library/nats 2.15.3-alpine 2.15.4-alpine "$P")"
run_case "docker v-prefixed minor"              true  docker "$M" "$(dep docker.io/prom/prometheus v3.16.0 v3.17.0 "$M")"
run_case "docker postgres major tag"            false docker "$X" "$(dep docker.io/library/postgres 17.6 18.1 "$X")"
run_case "docker date tag (sclorg)"             true  docker "$X" "$(dep quay.io/sclorg/postgresql-18-c9s 20261111 20261201 "$X")"
run_case "unparseable versions"                 false docker "" "$(dep foo/bar latest stable "")"
run_case "missing metadata"                     false maven "$P" ""

echo "classify-update tests: $pass passed, $failc failed"
(( failc == 0 ))
