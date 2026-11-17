#!/usr/bin/env bash
# Node.js toolchain check (docs/dependency-maintenance.md §5.3, concern "toolchain").
#
# Dependabot updates npm packages and actions/setup-node, but not the Node.js version itself. This script:
#   1. reports drift between web/.nvmrc (the major CI and contributors use), the `engines.node` floor in
#      web/package.json (and its copy in web/package-lock.json), and any setup-node step that hard-codes
#      `node-version` instead of reading web/.nvmrc;
#   2. compares web/.nvmrc with the newest Node.js LTS major on nodejs.org.
# A newer LTS major is a major upgrade: the PR it produces is labelled needs-review, never auto-merged.
#
# Usage: node-toolchain-check.sh [--apply]
#   --apply  move web/.nvmrc, engines (package.json + lock) and the "Node.js <major> LTS" prerequisite lines
#            to the target major (the newest LTS when newer, else the current major to repair drift).
#            Workflow files are reported, never edited (a PR token may not hold the workflows scope).
# Env: NODE_DIST_INDEX (default https://nodejs.org/dist/index.json; a local file path is accepted for tests)
# Output: $MAINT_OUT_DIR/node-toolchain.json, node-toolchain.md; step outputs
#   update_available, drift, current_major, latest_lts_major, latest_lts_version, target_floor
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need curl jq sed grep

NODE_DIST_INDEX="${NODE_DIST_INDEX:-https://nodejs.org/dist/index.json}"
NVMRC="$REPO_ROOT/web/.nvmrc"
PKG="$REPO_ROOT/web/package.json"
LOCK="$REPO_ROOT/web/package-lock.json"
DOCS=("$REPO_ROOT/README.md" "$REPO_ROOT/docs/tutorials/index.md")
apply=false
[[ "${1:-}" == "--apply" ]] && apply=true

major_of() { local v="${1#v}"; v="${v#>=}"; printf '%s' "${v%%.*}"; }

current="$(tr -d '[:space:]' <"$NVMRC")"
current_major="$(major_of "$current")"
engines="$(jq -r '.engines.node // ""' "$PKG")"
engines_major="$(major_of "$engines")"
lock_engines="$(jq -r '.packages[""].engines.node // ""' "$LOCK" 2>/dev/null || true)"

drift=()
[[ "$current_major" =~ ^[0-9]+$ ]] || drift+=("web/.nvmrc does not hold a Node.js major version ('$current')")
[[ "$engines" =~ ^\>=[0-9]+(\.[0-9]+){0,2}$ ]] || drift+=("web/package.json engines.node is '$engines', expected '>=<major>.<minor>'")
[[ "$engines_major" == "$current_major" ]] || drift+=("engines.node ($engines) is not on the web/.nvmrc major ($current_major)")
[[ "$lock_engines" == "$engines" ]] || drift+=("web/package-lock.json engines.node ($lock_engines) differs from package.json ($engines)")
while IFS= read -r hit; do
  drift+=("hard-coded node-version in ${hit#"$REPO_ROOT"/} (use node-version-file: web/.nvmrc)")
done < <(grep -rnE '^\s*node-version:' "$REPO_ROOT/.github/workflows" 2>/dev/null || true)

if [[ "$NODE_DIST_INDEX" == http* ]]; then index="$(fetch "$NODE_DIST_INDEX")"; else index="$(cat "$NODE_DIST_INDEX")"; fi
latest_lts_version="$(jq -r '[.[] | select(.lts != false) | .version | ltrimstr("v")] | sort_by(split(".") | map(tonumber)) | last // ""' <<<"$index")"
latest_lts_major="$(major_of "$latest_lts_version")"

update=false
target_major="$current_major"
if [[ "$latest_lts_major" =~ ^[0-9]+$ && "$current_major" =~ ^[0-9]+$ ]] && (( latest_lts_major > current_major )); then
  update=true
  target_major="$latest_lts_major"
fi
# Floor for engines: the first LTS release of the target major (the oldest version that still has LTS support),
# or the existing floor when staying on the current major.
if [[ "$update" == "true" ]]; then
  target_floor="$(jq -r --arg m "$target_major" '[.[] | select(.lts != false) | .version | ltrimstr("v") | select(startswith($m + "."))]
    | sort_by(split(".") | map(tonumber)) | first | split(".")[0:2] | join(".")' <<<"$index")"
else
  target_floor="${engines#>=}"
  [[ "$engines_major" == "$current_major" ]] || target_floor="${current_major}.0"
fi

if [[ "$apply" == "true" ]] && { [[ "$update" == "true" ]] || (( ${#drift[@]} > 0 )); }; then
  printf '%s\n' "$target_major" >"$NVMRC"
  tmp="$(mktemp)"
  jq --arg e ">=$target_floor" '.engines.node = $e' "$PKG" >"$tmp" && mv "$tmp" "$PKG"
  jq --arg e ">=$target_floor" '.packages[""].engines.node = $e' "$LOCK" >"$tmp" && mv "$tmp" "$LOCK"
  for f in "${DOCS[@]}"; do
    [[ -f "$f" ]] && sed -i.bak -E "s/Node\.js [0-9]+ LTS or newer/Node.js ${target_major} LTS or newer/g" "$f" && rm -f "$f.bak"
  done
  log "applied: web/.nvmrc=$target_major, engines.node=>=$target_floor"
fi

drift_json="$(printf '%s\n' "${drift[@]+"${drift[@]}"}" | jq -R . | jq -s 'map(select(length > 0))')"
jq -n --arg cur "$current" --arg eng "$engines" --arg lts "$latest_lts_version" --argjson u "$update" \
  --arg tf "$target_floor" --argjson d "$drift_json" \
  '{nvmrc:$cur, engines:$eng, latest_lts:$lts, update_available:$u, target_floor:$tf, drift:$d}' >"$MAINT_OUT_DIR/node-toolchain.json"
{
  echo "### Node.js toolchain (owned by upgrade-compat)"
  echo
  echo "| web/.nvmrc | engines.node | Newest LTS | Status |"
  echo "| --- | --- | --- | --- |"
  if [[ "$update" == "true" ]]; then
    status="newer LTS major available: $latest_lts_major (major upgrade, needs review)"
  elif (( ${#drift[@]} > 0 )); then
    status="drift (see below)"
  else
    status="up to date"
  fi
  echo "| $(md_cell "$current") | $(md_cell "$engines") | ${latest_lts_version:-?} | $status |"
  echo
  if (( ${#drift[@]} > 0 )); then
    printf -- '- %s\n' "${drift[@]}"
    echo
  fi
  if [[ "$update" == "true" ]]; then
    echo "Upgrading moves web/.nvmrc to $target_major and engines.node to \`>=$target_floor\`. CI then builds and tests the SPA"
    echo "on the new major. Also review \`@types/node\` and the Node.js row in docs/versions.md."
    echo
  fi
} >"$MAINT_OUT_DIR/node-toolchain.md"

gh_output update_available "$update"
gh_output drift "$( (( ${#drift[@]} > 0 )) && echo true || echo false)"
gh_output current_major "$current_major"
gh_output latest_lts_major "$latest_lts_major"
gh_output latest_lts_version "$latest_lts_version"
gh_output target_floor "$target_floor"
cat "$MAINT_OUT_DIR/node-toolchain.json"
