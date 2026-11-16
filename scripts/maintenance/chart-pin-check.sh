#!/usr/bin/env bash
# Compare Helm chart pins in deploy/platform/versions.env with the chart repository indexes.
#
# Pins are discovered generically: every <PREFIX>_CHART_VERSION key. For each prefix:
#   chart name  = <PREFIX>_CHART, else <PREFIX>_CHART_NAME, else lower-case prefix with "_" -> "-"
#                 (SPIRE_CRDS -> spire-crds)
#   repository  = <PREFIX>_CHART_REPO_URL or <PREFIX>_CHART_REPO, else the same keys of the nearest
#                 shorter prefix (SPIRE_CRDS -> SPIRE_CHART_REPO_URL), else a built-in default.
# Only stable chart versions (no semver pre-release suffix) are considered unless ALLOW_PRERELEASE=true.
#
# Image tag pins in the same file (<NAME>_TAG keys) are compared with the GitHub tags of the
# project listed in TAG_SOURCES ("KEY=owner/repo ...", default
# "SPIFFE_CSI_DRIVER_TAG=spiffe/spiffe-csi"). Keys without a source are reported as unchecked.
#
# Usage: chart-pin-check.sh [--apply]
#   --apply  rewrite VERSIONS_ENV in place with the newest versions (used on upgrade/platform-charts)
# Output: $MAINT_OUT_DIR/charts.json, charts.md; step outputs updates_available, bump_level
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need curl jq yq sort git

apply=false
[[ "${1:-}" == "--apply" ]] && apply=true

default_repo() {
  case "$1" in
    KEDA) echo "https://kedacore.github.io/charts" ;;
    SPIRE) echo "https://spiffe.github.io/helm-charts-hardened" ;;
    *) echo "" ;;
  esac
}

if [[ ! -f "$VERSIONS_ENV" ]]; then
  warn "versions file not found: $VERSIONS_ENV (set VERSIONS_ENV)"
  echo '{"charts":[],"error":"versions.env not found"}' >"$MAINT_OUT_DIR/charts.json"
  printf '### Helm chart pins\n\n%s not found; nothing checked.\n\n' "\`${VERSIONS_ENV#"$REPO_ROOT"/}\`" >"$MAINT_OUT_DIR/charts.md"
  gh_output updates_available false
  gh_output bump_level none
  exit 0
fi

repo_for() {
  local p="$1" r
  while :; do
    r="$(env_value "$VERSIONS_ENV" "${p}_CHART_REPO_URL")"
    [[ -n "$r" ]] || r="$(env_value "$VERSIONS_ENV" "${p}_CHART_REPO")"
    [[ "$r" == http* ]] && { printf '%s' "${r%/}"; return; }
    r="$(default_repo "$p")"
    [[ -n "$r" ]] && { printf '%s' "$r"; return; }
    [[ "$p" == *_* ]] || return 0
    p="${p%_*}"
  done
}

# Cached per run: one download per repository.
index_file() {
  local repo="$1" f
  f="$MAINT_OUT_DIR/index-$(printf '%s' "$repo" | tr -c 'A-Za-z0-9' '_').yaml"
  if [[ ! -s "$f" || "$f" -ot "$MAINT_OUT_DIR/.run-start" ]]; then
    fetch "$repo/index.yaml" -o "$f"
  fi
  printf '%s' "$f"
}
touch "$MAINT_OUT_DIR/.run-start"

rank() { case "$1" in major) echo 3;; minor) echo 2;; patch) echo 1;; *) echo 0;; esac; }

charts_json="[]"
rows=""
overall=none
updates=false
tmp_env="$(mktemp)"
cp "$VERSIONS_ENV" "$tmp_env"

while IFS= read -r key; do
  prefix="${key%_CHART_VERSION}"
  current="$(env_value "$VERSIONS_ENV" "$key")"
  name="$(env_value "$VERSIONS_ENV" "${prefix}_CHART")"
  [[ -n "$name" ]] || name="$(env_value "$VERSIONS_ENV" "${prefix}_CHART_NAME")"
  [[ -n "$name" ]] || name="$(printf '%s' "$prefix" | tr '[:upper:]_' '[:lower:]-')"
  repo="$(repo_for "$prefix")"
  if [[ -z "$repo" ]]; then
    warn "no repository for $key"
    rows+="| \`$name\` | $current | ? | — | no \`${prefix}_CHART_REPO_URL\` |"$'\n'
    continue
  fi
  idx="$(index_file "$repo")"
  versions="$(yq ".entries[\"$name\"][].version" "$idx" 2>/dev/null || true)"
  if [[ -z "$versions" ]]; then
    rows+="| \`$name\` | $current | ? | — | chart not found in $repo |"$'\n'
    continue
  fi
  if [[ "$ALLOW_PRERELEASE" == "true" ]]; then
    latest="$(printf '%s\n' "$versions" | max_version)"
  else
    latest="$(printf '%s\n' "$versions" | grep -Ev -- '-' | max_version)"
  fi
  app="$(yq ".entries[\"$name\"][] | select(.version == \"$latest\") | .appVersion" "$idx" | head -n1)"
  kube="$(yq ".entries[\"$name\"][] | select(.version == \"$latest\") | .kubeVersion // \"\"" "$idx" | head -n1)"
  level=none
  if version_gt "$latest" "$current"; then
    level="$(bump_level "$current" "$latest")"
    # SemVer 0.x: a minor bump may break (the SPIRE charts are 0.x), so it is treated as major.
    if [[ "$level" == "minor" && "$(vpart "$current" 1)" == "0" ]]; then level=major; fi
    updates=true
    if (( $(rank "$level") > $(rank "$overall") )); then overall="$level"; fi
    # Rewrite only this key's value; keep everything else byte for byte.
    sed -E "s|^([[:space:]]*${key}=)[\"']?[^\"'[:space:]#]*[\"']?|\1${latest}|" "$tmp_env" >"$tmp_env.new" && mv "$tmp_env.new" "$tmp_env"
  fi
  charts_json="$(jq --arg k "$key" --arg n "$name" --arg r "$repo" --arg c "$current" --arg l "$latest" \
      --arg a "$app" --arg kv "$kube" --arg lv "$level" \
      '. + [{key:$k, chart:$n, repo:$r, pinned:$c, latest_stable:$l, app_version:$a, kube_version:$kv, bump:$lv}]' <<<"$charts_json")"
  rows+="| \`$name\` | $current | $latest (app $app) | $level | $(md_cell "${kube:-not declared}") |"$'\n'
done < <({ grep -Eo '^[[:space:]]*[A-Z0-9_]+_CHART_VERSION=' "$VERSIONS_ENV" || true; } | tr -d ' =' | sort -u)

# Line number of a nested mapping key given a yq path like .["a-b"].image.tag (block-style YAML).
yaml_key_line() {
  local path="$1" file="$2" segs
  segs="$(printf '%s' "$path" | sed -E 's/\["([^"]+)"\]/.\1/g; s/^\.//; s/\.+/ /g')"
  awk -v segs="$segs" '
    BEGIN { n = split(segs, k, " "); depth = 1; parent_indent = -1 }
    /^[[:space:]]*(#|$)/ { next }
    {
      match($0, /^[ ]*/); ind = RLENGTH
      if (depth > 1 && ind <= parent_indent) { exit }      # left the parent block
      line = substr($0, ind + 1)
      if (index(line, k[depth] ":") == 1) {
        if (depth == n) { print NR; exit }
        parent_indent = ind; depth++
      }
    }' "$file"
}

# ---- image tag pins (<NAME>_TAG)
TAG_SOURCES="${TAG_SOURCES:-SPIFFE_CSI_DRIVER_TAG=spiffe/spiffe-csi}"
# Helm values paths that mirror a tag pin; --apply updates them in every
# deploy/platform/*/values*.yaml where the path exists.
TAG_VALUES="${TAG_VALUES:-SPIFFE_CSI_DRIVER_TAG=.[\"spiffe-csi-driver\"].image.tag}"
values_dir="$(dirname "$VERSIONS_ENV")"
declare -a tag_value_updates=()
tag_rows=""
tags_json="[]"
while IFS= read -r key; do
  [[ -z "$key" ]] && continue
  current="$(env_value "$VERSIONS_ENV" "$key")"
  src=""
  for entry in $TAG_SOURCES; do [[ "${entry%%=*}" == "$key" ]] && src="${entry#*=}"; done
  if [[ -z "$src" ]]; then
    tag_rows+="| \`$key\` | $current | — | unchecked (no TAG_SOURCES entry) |"$'\n'
    continue
  fi
  latest="$(git ls-remote --tags --refs "https://github.com/$src" 2>/dev/null | awk '{print $2}' \
    | sed 's#refs/tags/##; s#^v##' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | max_version || true)"
  level=none
  if [[ -n "$latest" ]] && version_gt "$latest" "${current#v}"; then
    level="$(bump_level "${current#v}" "$latest")"
    if [[ "$level" == "minor" && "$(vpart "$current" 1)" == "0" ]]; then level=major; fi
    updates=true
    if (( $(rank "$level") > $(rank "$overall") )); then overall="$level"; fi
    sed -E "s|^([[:space:]]*${key}=)[\"']?[^\"'[:space:]#]*[\"']?|\1${latest}|" "$tmp_env" >"$tmp_env.new" && mv "$tmp_env.new" "$tmp_env"
    for entry in $TAG_VALUES; do
      [[ "${entry%%=*}" == "$key" ]] && tag_value_updates+=("${entry#*=}=$latest")
    done
  fi
  tags_json="$(jq --arg k "$key" --arg s "$src" --arg c "$current" --arg l "$latest" --arg lv "$level" \
    '. + [{key:$k, source:$s, pinned:$c, latest_stable:$l, bump:$lv}]' <<<"$tags_json")"
  tag_rows+="| \`$key\` | $current | ${latest:-?} (github.com/$src) | $level |"$'\n'
done < <({ grep -Eo '^[[:space:]]*[A-Z0-9_]+_TAG=' "$VERSIONS_ENV" || true; } | tr -d ' =' | sort -u)

if [[ "$apply" == "true" && "$updates" == "true" ]]; then
  cp "$tmp_env" "$VERSIONS_ENV"
  log "updated $VERSIONS_ENV"
  for upd in "${tag_value_updates[@]+"${tag_value_updates[@]}"}"; do
    ypath="${upd%%=*}"; val="${upd#*=}"
    for vf in "$values_dir"/*/values*.yaml; do
      [[ -f "$vf" ]] || continue
      if [[ "$(yq "$ypath // \"\"" "$vf")" != "" ]]; then
        # Edit only that line (yq -i would drop blank lines and reflow the file).
        ln="$(yaml_key_line "$ypath" "$vf")"
        if [[ -z "$ln" ]]; then warn "could not locate $ypath in $vf; not edited"; continue; fi
        sed -E "${ln}s|^([[:space:]]*[A-Za-z0-9_-]+:[[:space:]]*)[\"']?[^\"'[:space:]#]*[\"']?|\1\"${val}\"|" "$vf" >"$vf.new" && mv "$vf.new" "$vf"
        log "updated $ypath (line $ln) in $vf"
      fi
    done
  done
fi
rm -f "$tmp_env"

jq -n --argjson charts "$charts_json" --argjson tags "$tags_json" --arg level "$overall" --argjson updates "$updates" \
  '{charts:$charts, image_tags:$tags, updates_available:$updates, bump_level:$level}' >"$MAINT_OUT_DIR/charts.json"
{
  echo "### Helm chart pins (\`deploy/platform/versions.env\`, owned by upgrade-compat)"
  echo
  echo "| Chart | Pinned | Latest stable | Bump | Chart kubeVersion |"
  echo "| --- | --- | --- | --- | --- |"
  printf '%s' "$rows"
  echo
  if [[ -n "$tag_rows" ]]; then
    echo "| Image tag pin | Pinned | Latest release | Bump |"
    echo "| --- | --- | --- | --- |"
    printf '%s' "$tag_rows"
    echo
    echo "\`--apply\` also updates the mirrored values paths (TAG_VALUES) in \`deploy/platform/*/values*.yaml\`."
    echo
  fi
  if [[ "$updates" == "true" ]]; then
    echo "Highest bump: **$overall**. Check the chart's tested Kubernetes range against docs/versions.md §5 and verify on the demo cluster before the next event."
    echo
  fi
} >"$MAINT_OUT_DIR/charts.md"

gh_output updates_available "$updates"
gh_output bump_level "$overall"
cat "$MAINT_OUT_DIR/charts.json"
