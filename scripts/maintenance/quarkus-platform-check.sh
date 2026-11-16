#!/usr/bin/env bash
# Discover Quarkus platform releases and compare them with the pinned quarkus.platform.version.
#
# Source: $QUARKUS_REGISTRY/client/platforms (the Quarkus extension registry client API).
# The registry returns pre-release streams (e.g. 4.0 with 4.0.0.CR1) even without any query
# parameter, so pre-releases are filtered here (lib.sh is_stable) unless ALLOW_PRERELEASE=true.
#
# Output: $MAINT_OUT_DIR/quarkus-platform.json and quarkus-platform.md; GitHub step outputs
#   current_version, current_stream, latest_in_stream, patch_available,
#   newest_lts_stream, newest_lts_version, newer_lts_available, upgrade_level (none|minor|major)
#
# Overrides: POM_FILE, PLATFORM_VERSION (skip reading the POM), PLATFORMS_JSON (use a local file).
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need curl jq yq sort

current="${PLATFORM_VERSION:-$(pom_property quarkus.platform.version)}"
[[ -n "$current" ]] || die "quarkus.platform.version not found in $POM_FILE"
current_stream="$(stream_of "$current")"

raw="$MAINT_OUT_DIR/quarkus-registry-platforms.json"
if [[ -n "${PLATFORMS_JSON:-}" ]]; then
  cp "$PLATFORMS_JSON" "$raw"
else
  fetch "$QUARKUS_REGISTRY/client/platforms" -o "$raw"
fi

# Flatten: one line per (stream, lts, version) for the community platform.
flat="$(jq -r '
  .platforms[] | select(."platform-key" == "io.quarkus.platform")
  | .streams[] | .id as $id | (.lts // false) as $lts
  | .releases[]? | [$id, ($lts|tostring), .version] | @tsv' "$raw")"
[[ -n "$flat" ]] || die "registry response contains no io.quarkus.platform releases"

registry_current_stream="$(jq -r '.platforms[] | select(."platform-key"=="io.quarkus.platform") | ."current-stream-id" // ""' "$raw")"

# Keep only acceptable versions.
accepted=""
while IFS=$'\t' read -r sid lts ver; do
  if [[ "$ALLOW_PRERELEASE" == "true" ]] || is_stable "$ver"; then
    accepted+="${sid}"$'\t'"${lts}"$'\t'"${ver}"$'\n'
  fi
done <<<"$flat"

current_lts="$(awk -F'\t' -v s="$current_stream" '$1==s {print $2; exit}' <<<"$accepted")"
latest_in_stream="$(awk -F'\t' -v s="$current_stream" '$1==s {print $3}' <<<"$accepted" | max_version)"
patch_available=false
if [[ -n "$latest_in_stream" ]] && version_gt "$latest_in_stream" "$current"; then
  patch_available=true
fi

newest_lts_version="$(awk -F'\t' '$2=="true" {print $3}' <<<"$accepted" | max_version)"
newest_lts_stream=""
[[ -n "$newest_lts_version" ]] && newest_lts_stream="$(stream_of "$newest_lts_version")"

newer_lts_available=false
upgrade_level=none
if [[ -n "$newest_lts_stream" ]] && version_gt "$newest_lts_stream.0" "$current_stream.0"; then
  newer_lts_available=true
  if [[ "$(vpart "$newest_lts_stream" 1)" != "$(vpart "$current_stream" 1)" ]]; then
    upgrade_level=major
  else
    upgrade_level=minor
  fi
fi

# Newer non-LTS streams are reported only.
newer_non_lts="$(awk -F'\t' '$2!="true" {print $1"\t"$3}' <<<"$accepted" | while IFS=$'\t' read -r s v; do
  if version_gt "$s.0" "$current_stream.0"; then printf '%s\n' "$v"; fi
done | sort -V | paste -sd, -)"
# Pre-release streams seen (always reported, never acted upon unless opted in).
prerelease_seen="$(awk -F'\t' '{print $3}' <<<"$flat" | while IFS= read -r v; do is_stable "$v" || printf '%s\n' "$v"; done | sort -V | paste -sd, -)"

migration_guide=""
[[ "$newer_lts_available" == "true" ]] && migration_guide="https://github.com/quarkusio/quarkus/wiki/Migration-Guide-${newest_lts_stream}"

jq -n \
  --arg current "$current" --arg current_stream "$current_stream" --arg current_lts "${current_lts:-unknown}" \
  --arg registry_current_stream "$registry_current_stream" \
  --arg latest_in_stream "$latest_in_stream" --argjson patch_available "$patch_available" \
  --arg newest_lts_stream "$newest_lts_stream" --arg newest_lts_version "$newest_lts_version" \
  --argjson newer_lts_available "$newer_lts_available" --arg upgrade_level "$upgrade_level" \
  --arg newer_non_lts "$newer_non_lts" --arg prerelease_seen "$prerelease_seen" \
  --arg migration_guide "$migration_guide" --arg allow_prerelease "$ALLOW_PRERELEASE" \
  '{current_version:$current, current_stream:$current_stream, current_stream_lts:$current_lts,
    registry_current_stream:$registry_current_stream,
    latest_in_current_stream:$latest_in_stream, patch_available:$patch_available,
    newest_lts_stream:$newest_lts_stream, newest_lts_version:$newest_lts_version,
    newer_lts_available:$newer_lts_available, upgrade_level:$upgrade_level,
    newer_non_lts:($newer_non_lts | split(",") | map(select(. != ""))),
    prereleases_ignored:($prerelease_seen | split(",") | map(select(. != ""))),
    allow_prerelease:($allow_prerelease == "true"), migration_guide:$migration_guide}' \
  >"$MAINT_OUT_DIR/quarkus-platform.json"

{
  echo "### Quarkus platform"
  echo
  echo "| Item | Value |"
  echo "| --- | --- |"
  echo "| Pinned \`quarkus.platform.version\` | \`$current\` (stream $current_stream, LTS: ${current_lts:-not listed}) |"
  echo "| Latest release in stream $current_stream | ${latest_in_stream:-not listed by the registry} |"
  if [[ "$patch_available" == "true" ]]; then
    echo "| Patch in current stream | $latest_in_stream available — owned by **Dependabot** (quarkus-platform group) |"
  else
    echo "| Patch in current stream | up to date |"
  fi
  echo "| Newest LTS stream | ${newest_lts_stream:-none} (${newest_lts_version:-n/a}) |"
  if [[ "$newer_lts_available" == "true" ]]; then
    echo "| Stream upgrade | **$current_stream → $newest_lts_stream ($upgrade_level)** — owned by upgrade-compat; [migration guide]($migration_guide) |"
  else
    echo "| Stream upgrade | none (already on the newest LTS stream) |"
  fi
  echo "| Newer non-LTS releases (report only) | ${newer_non_lts:-none} |"
  echo "| Pre-releases ignored | ${prerelease_seen:-none} |"
  echo
} >"$MAINT_OUT_DIR/quarkus-platform.md"

gh_output current_version "$current"
gh_output current_stream "$current_stream"
gh_output latest_in_stream "$latest_in_stream"
gh_output patch_available "$patch_available"
gh_output newest_lts_stream "$newest_lts_stream"
gh_output newest_lts_version "$newest_lts_version"
gh_output newer_lts_available "$newer_lts_available"
gh_output upgrade_level "$upgrade_level"

cat "$MAINT_OUT_DIR/quarkus-platform.json"
