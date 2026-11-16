#!/usr/bin/env bash
# Compare the Maven version in .mvn/wrapper/maven-wrapper.properties with the latest stable
# Maven in the same major.minor line (default 3.9) on Maven Central.
#
# Usage: maven-wrapper-check.sh [--apply]
#   --apply  run ./mvnw -B -N wrapper:wrapper -Dmaven=<latest> (used on upgrade/maven-wrapper)
# Env: MAVEN_LINE (default 3.9)
# Output: $MAINT_OUT_DIR/maven-wrapper.json, maven-wrapper.md; step outputs
#   update_available, current, latest, bump_level
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need curl jq yq sort

MAVEN_LINE="${MAVEN_LINE:-3.9}"
apply=false
[[ "${1:-}" == "--apply" ]] && apply=true

current=""
if [[ -f "$WRAPPER_PROPERTIES" ]]; then
  url="$(env_value "$WRAPPER_PROPERTIES" distributionUrl)"
  current="$(printf '%s' "$url" | sed -nE 's#.*/apache-maven-([0-9][0-9A-Za-z.-]*)-bin\.(zip|tar\.gz)$#\1#p')"
fi

latest="$(maven_versions org.apache.maven:apache-maven | filter_stable \
  | grep -E "^${MAVEN_LINE//./\\.}\.[0-9]+$" | max_version || true)"

update=false
level=none
if [[ -n "$current" && -n "$latest" ]] && version_gt "$latest" "$current"; then
  update=true
  level="$(bump_level "$current" "$latest")"
fi

if [[ "$apply" == "true" && "$update" == "true" ]]; then
  cd "$REPO_ROOT"
  [[ -x ./mvnw ]] || die "./mvnw not found"
  ./mvnw -B -ntp -N wrapper:wrapper "-Dmaven=$latest"
fi

jq -n --arg c "$current" --arg l "$latest" --arg line "$MAVEN_LINE" --argjson u "$update" --arg lv "$level" \
  '{current:$c, latest_in_line:$l, line:$line, update_available:$u, bump_level:$lv}' >"$MAINT_OUT_DIR/maven-wrapper.json"
{
  echo "### Maven Wrapper (owned by upgrade-compat)"
  echo
  echo "| Pinned | Latest $MAVEN_LINE.x | Status |"
  echo "| --- | --- | --- |"
  if [[ -z "$current" ]]; then
    echo "| — | ${latest:-?} | \`${WRAPPER_PROPERTIES#"$REPO_ROOT"/}\` not found or no distributionUrl |"
  elif [[ "$update" == "true" ]]; then
    echo "| $current | $latest | update available ($level) |"
  else
    echo "| $current | ${latest:-?} | up to date |"
  fi
  echo
} >"$MAINT_OUT_DIR/maven-wrapper.md"

gh_output update_available "$update"
gh_output current "$current"
gh_output latest "$latest"
gh_output bump_level "$level"
cat "$MAINT_OUT_DIR/maven-wrapper.json"
