#!/usr/bin/env bash
# Classify a Dependabot pull request as auto-merge eligible or not, from dependabot/fetch-metadata
# outputs. Pure function of its inputs: no network, no repository access, no PR code.
#
# Input (environment, as produced by dependabot/fetch-metadata v3):
#   UPDATE_TYPE          version-update:semver-{patch,minor,major} or empty (highest across the PR)
#   PACKAGE_ECOSYSTEM    maven | npm_and_yarn | github_actions | docker | ...
#   DEPENDENCY_NAMES     comma separated
#   PREVIOUS_VERSION     version before (single-dependency PRs)
#   NEW_VERSION          version after  (single-dependency PRs)
#   UPDATED_DEPS_JSON    JSON array; each item has dependencyName, prevVersion, newVersion, updateType
#
# Output (stdout and $GITHUB_OUTPUT): eligible=true|false, reason=<one line>
#
# Rules (docs/dependency-maintenance.md §1, §5.2):
#   - any dependency with a major bump                      -> not eligible
#   - io.quarkus* : the stream (major.minor) must not change (four-part versions are not reliably
#                   classified, so the stream is compared directly)
#   - docker      : the leading numeric tag component must not change (e.g. postgres 17 -> 18 is a
#                   major); a pure date tag (YYYYMMDD, as used by quay.io/sclorg images, whose
#                   major version is part of the image name) counts as a patch
#   - a dependency whose versions cannot be parsed          -> not eligible (unclassifiable)
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need jq

UPDATE_TYPE="${UPDATE_TYPE:-}"
PACKAGE_ECOSYSTEM="${PACKAGE_ECOSYSTEM:-}"
DEPENDENCY_NAMES="${DEPENDENCY_NAMES:-}"
PREVIOUS_VERSION="${PREVIOUS_VERSION:-}"
NEW_VERSION="${NEW_VERSION:-}"
UPDATED_DEPS_JSON="${UPDATED_DEPS_JSON:-}"

emit() {
  printf 'eligible=%s\nreason=%s\n' "$1" "$2"
  gh_output eligible "$1"
  gh_output reason "$2"
  exit 0
}

# Strip a docker tag down to its version-ish part: "v3.16.0" -> "3.16.0", "2.15.3-alpine" -> "2.15.3".
norm_version() {
  local v="${1#v}"
  printf '%s' "$v" | sed -E 's/^([0-9]+(\.[0-9]+)*).*/\1/'
}

classify_one() { # name prev new -> prints "ok <level>" or "no <reason>"
  local name="$1" prev="$2" new="$3" p n level
  if [[ -z "$prev" || -z "$new" ]]; then echo "no unclassifiable: missing versions for $name"; return; fi
  if [[ "$PACKAGE_ECOSYSTEM" == "docker" && "$prev" =~ ^[0-9]{8}$ && "$new" =~ ^[0-9]{8}$ ]]; then
    echo "ok patch"; return
  fi
  p="$(norm_version "$prev")"; n="$(norm_version "$new")"
  if [[ ! "$p" =~ ^[0-9]+ || ! "$n" =~ ^[0-9]+ ]]; then echo "no unclassifiable: $name $prev -> $new"; return; fi
  case "$name" in
    io.quarkus:*|io.quarkus.platform:*)
      if [[ "$(stream_of "$p")" != "$(stream_of "$n")" ]]; then
        echo "no Quarkus stream change $(stream_of "$p") -> $(stream_of "$n") for $name (owned by upgrade-compat)"; return
      fi ;;
  esac
  level="$(bump_level "$p" "$n")"
  case "$level" in
    major) echo "no major update: $name $prev -> $new" ;;
    minor|patch|none) echo "ok $level" ;;
    *) echo "no unclassifiable: $name $prev -> $new" ;;
  esac
}

# Docker tags are not semver (date tags, suffixes): for docker the per-dependency rules below
# decide, not fetch-metadata's update-type.
if [[ "$UPDATE_TYPE" == "version-update:semver-major" && "$PACKAGE_ECOSYSTEM" != "docker" ]]; then
  emit false "major update (${DEPENDENCY_NAMES:-unknown}) needs human review"
fi
case "$UPDATE_TYPE" in
  ""|version-update:semver-patch|version-update:semver-minor|version-update:semver-major) ;;
  *) emit false "unknown update-type '$UPDATE_TYPE'" ;;
esac

# Build the list of (name, prev, new) triples.
triples=""
if [[ -n "$UPDATED_DEPS_JSON" && "$UPDATED_DEPS_JSON" != "null" ]] \
   && jq -e 'type == "array" and length > 0' >/dev/null 2>&1 <<<"$UPDATED_DEPS_JSON"; then
  triples="$(jq -r '.[] | [.dependencyName // "", .prevVersion // "", .newVersion // "", .updateType // ""] | @tsv' <<<"$UPDATED_DEPS_JSON")"
elif [[ -n "$DEPENDENCY_NAMES" ]]; then
  triples="$(printf '%s\t%s\t%s\t%s' "${DEPENDENCY_NAMES%%,*}" "$PREVIOUS_VERSION" "$NEW_VERSION" "$UPDATE_TYPE")"
fi
[[ -n "$triples" ]] || emit false "no dependency metadata"

count=0
while IFS=$'\t' read -r name prev new utype; do
  [[ -z "$name" ]] && continue
  count=$((count + 1))
  if [[ "$utype" == "version-update:semver-major" && "$PACKAGE_ECOSYSTEM" != "docker" ]]; then
    emit false "major update: $name ${prev:-?} -> ${new:-?}"
  fi
  result="$(classify_one "$name" "$prev" "$new")"
  if [[ "$result" == no\ * ]]; then
    emit false "${result#no }"
  fi
done <<<"$triples"

emit true "all $count dependency update(s) are patch/minor within policy"
