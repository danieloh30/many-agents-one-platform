#!/usr/bin/env bash
# Report what the Quarkus platform member BOM manages for LangChain4j, how far it lags the
# standalone Quarkus LangChain4j release (informational: the project follows the platform), and
# the state of independently versioned libraries (owned by Dependabot).
#
# Usage: extension-compat-check.sh [platform-version ...]
#   Default: the pinned quarkus.platform.version, plus $CANDIDATE_VERSION if set and different.
# Output: $MAINT_OUT_DIR/extension-compat.json and extension-compat.md
#
# INDEPENDENT_LIBS (env) maps POM properties to group:artifact, space separated:
#   default "jnats.version=io.nats:jnats bouncycastle-lts.version=org.bouncycastle:bcprov-lts8on"
#   (CloudEvents has no pin of its own: the project uses an in-repo binary-mode codec, ADR-0016.)
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need curl jq yq sort

INDEPENDENT_LIBS="${INDEPENDENT_LIBS:-jnats.version=io.nats:jnats bouncycastle-lts.version=org.bouncycastle:bcprov-lts8on}"

pinned="$(pom_property quarkus.platform.version)"
declare -a candidates=()
if [[ $# -gt 0 ]]; then
  candidates=("$@")
else
  [[ -n "$pinned" ]] && candidates+=("$pinned")
  if [[ -n "${CANDIDATE_VERSION:-}" && "${CANDIDATE_VERSION}" != "$pinned" ]]; then
    candidates+=("$CANDIDATE_VERSION")
  fi
fi
[[ ${#candidates[@]} -gt 0 ]] || die "no platform version (pom.xml missing and no argument given)"

standalone="$(maven_latest io.quarkiverse.langchain4j:quarkus-langchain4j-core || true)"

managed_version() { # bom-file group artifact
  yq -p xml -o y "[.project.dependencyManagement.dependencies.dependency[]
      | select(.groupId == \"$2\" and .artifactId == \"$3\" and ((.classifier // \"\") == \"\"))
      | .version] | .[0] // \"\"" "$1"
}

platform_json="[]"
md_rows=""
for pv in "${candidates[@]}"; do
  bom="$MAINT_OUT_DIR/quarkus-langchain4j-bom-$pv.pom"
  url="$MAVEN_CENTRAL/io/quarkus/platform/quarkus-langchain4j-bom/$pv/quarkus-langchain4j-bom-$pv.pom"
  if ! fetch "$url" -o "$bom"; then
    warn "member BOM not found: $url"
    platform_json="$(jq --arg pv "$pv" '. + [{platform_version:$pv, error:"quarkus-langchain4j-bom not published for this platform version"}]' <<<"$platform_json")"
    md_rows+="| \`$pv\` | not published | — | — | — |"$'\n'
    continue
  fi
  qlc4j="$(managed_version "$bom" io.quarkiverse.langchain4j quarkus-langchain4j-core)"
  lc4j="$(managed_version "$bom" dev.langchain4j langchain4j-core)"
  lag="none"
  if [[ -n "$standalone" && -n "$qlc4j" ]] && version_gt "$standalone" "$qlc4j"; then
    lag="$(bump_level "$qlc4j" "$standalone") ($qlc4j → $standalone)"
  fi
  platform_json="$(jq --arg pv "$pv" --arg q "$qlc4j" --arg l "$lc4j" --arg s "$standalone" --arg lag "$lag" \
    '. + [{platform_version:$pv, quarkus_langchain4j:$q, langchain4j:$l, standalone_latest:$s, platform_lags_standalone_by:$lag}]' <<<"$platform_json")"
  md_rows+="| \`$pv\` | ${qlc4j:-?} | ${lc4j:-?} | ${standalone:-?} | $lag |"$'\n'
done

libs_json="[]"
lib_rows=""
for entry in $INDEPENDENT_LIBS; do
  prop="${entry%%=*}"; ga="${entry#*=}"
  cur=""
  [[ -f "$POM_FILE" ]] && cur="$(pom_property "$prop")"
  latest="$(maven_latest "$ga" || true)"
  status="UNAVAILABLE: property not found in pom.xml (update INDEPENDENT_LIBS)"
  if [[ -n "$cur" ]]; then
    if [[ -n "$latest" ]] && version_gt "$latest" "$cur"; then
      status="update available ($(bump_level "$cur" "$latest")) — Dependabot will propose it"
    else
      status="up to date"
    fi
  fi
  libs_json="$(jq --arg p "$prop" --arg ga "$ga" --arg c "$cur" --arg l "$latest" --arg s "$status" \
    '. + [{property:$p, coordinates:$ga, pinned:$c, latest_stable:$l, owner:"dependabot", status:$s}]' <<<"$libs_json")"
  lib_rows+="| \`$prop\` | \`$ga\` | ${cur:-—} | ${latest:-?} | Dependabot | $status |"$'\n'
done

jq -n --argjson platform "$platform_json" --argjson libs "$libs_json" \
  '{platform_member_bom: $platform, independent_libraries: $libs}' >"$MAINT_OUT_DIR/extension-compat.json"

{
  echo "### Platform member BOM (LangChain4j)"
  echo
  echo "Quarkus LangChain4j always comes from \`io.quarkus.platform:quarkus-langchain4j-bom\` at the platform version; the lag is informational."
  echo
  echo "| Platform | quarkus-langchain4j (managed) | LangChain4j (managed) | Standalone latest stable | Platform lags standalone by |"
  echo "| --- | --- | --- | --- | --- |"
  printf '%s' "$md_rows"
  echo
  echo "### Independently versioned libraries"
  echo
  echo "| Property | Coordinates | Pinned | Latest stable | Owner | Status |"
  echo "| --- | --- | --- | --- | --- | --- |"
  printf '%s' "$lib_rows"
  echo
} >"$MAINT_OUT_DIR/extension-compat.md"

cat "$MAINT_OUT_DIR/extension-compat.json"
