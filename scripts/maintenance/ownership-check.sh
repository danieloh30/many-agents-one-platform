#!/usr/bin/env bash
# Enforce the single-owner invariant between .github/dependabot.yml and
# .github/upgrade-ownership.yml (docs/dependency-maintenance.md §2).
#
# Fails when:
#   1. a workflow-owned Maven coordinate lacks an identical `ignore` rule in the maven entry;
#   2. a Dependabot `ignore` rule is neither workflow ownership nor a documented hold;
#   3. a Dockerfile FROM or a deploy/base/** `image:` lives in a directory no docker entry covers;
#   4. deploy/base uses a Kustomize `images:` transformer (Dependabot cannot see it);
#   5. a workflow-owned file is in a directory covered by an ecosystem listed in `not-covered-by`;
#   6. a compose file exists without a docker-compose entry;
#   7. a module POM redefines quarkus.platform.version, or the standalone Quarkus LangChain4j BOM
#      is imported anywhere.
# Warns (report only) about image references elsewhere under deploy/ that no engine covers.
#
# Directory globs are matched with bash patterns, where `*` also crosses `/`. That is a superset
# of Dependabot's matcher; GitHub's own expansion stays authoritative.
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
need yq jq grep find

cd "$REPO_ROOT"
[[ -f "$DEPENDABOT_FILE" ]] || die "missing $DEPENDABOT_FILE"
[[ -f "$OWNERSHIP_FILE" ]] || die "missing $OWNERSHIP_FILE"

errors=0
fail() { printf 'FAIL: %s\n' "$*"; errors=$((errors + 1)); }
ok()   { printf 'ok:   %s\n' "$*"; }
note() { printf 'warn: %s\n' "$*"; }

dep_json="$(yq -o json '.' "$DEPENDABOT_FILE")"
own_json="$(yq -o json '.' "$OWNERSHIP_FILE")"

# Canonical form of an ignore rule: ecosystem|name|sorted,update,types ("*" = all types).
canon_rules_dependabot="$(jq -r '
  .updates[] | ."package-ecosystem" as $e | (.ignore // [])[]
  | [$e, ."dependency-name", ((."update-types" // ["*"]) | sort | join(","))] | join("|")' <<<"$dep_json" | sort -u)"
canon_owned_maven="$(jq -r '
  (.maven // [])[] | ["maven", ."dependency-name", ((."update-types" // ["*"]) | sort | join(","))] | join("|")' <<<"$own_json" | sort -u)"
canon_holds="$(jq -r '
  (.holds // [])[] | [.ecosystem, ."dependency-name", ((."update-types" // ["*"]) | sort | join(","))] | join("|")' <<<"$own_json" | sort -u)"

echo "== 1. workflow-owned Maven coordinates have matching Dependabot ignore rules"
while IFS= read -r rule; do
  [[ -z "$rule" ]] && continue
  if grep -Fxq "$rule" <<<"$canon_rules_dependabot"; then ok "$rule"; else fail "no identical maven ignore rule in dependabot.yml for: $rule"; fi
done <<<"$canon_owned_maven"

echo "== 2. every Dependabot ignore rule is ownership or a documented hold"
while IFS= read -r rule; do
  [[ -z "$rule" ]] && continue
  if grep -Fxq "$rule" <<<"$canon_owned_maven" || grep -Fxq "$rule" <<<"$canon_holds"; then
    ok "$rule"
  else
    fail "ignore rule not declared in upgrade-ownership.yml (maven/holds): $rule"
  fi
done <<<"$canon_rules_dependabot"

# Directory patterns for an ecosystem, normalised to repo-relative ("" = root).
patterns_for() {
  jq -r --arg e "$1" '.updates[] | select(."package-ecosystem" == $e)
    | ((.directories // []) + (if .directory then [.directory] else [] end))[]' <<<"$dep_json" \
    | sed -E 's#^/+##; s#/+$##'
}
covered_by() { # ecosystem dir
  local p d="$2"
  local p0
  while IFS= read -r p; do
    # "**/" may match zero directories (globstar semantics); bash treats ** like *, so also try
    # the pattern with every "**/" removed.
    p0="${p//\*\*\//}"
    # shellcheck disable=SC2053  # intentional glob match
    if [[ "$d" == $p || "$d" == $p0 ]]; then return 0; fi
  done < <(patterns_for "$1")
  return 1
}
rel_dir() { local d; d="$(dirname "${1#./}")"; [[ "$d" == "." ]] && d=""; printf '%s' "$d"; }

echo "== 3. container image references are covered by a docker entry"
prune=(-path ./node_modules -o -path '*/node_modules' -o -path '*/target' -o -path ./.git -o -path ./web/dist)
dockerfiles="$(find . \( "${prune[@]}" \) -prune -o -type f \( -name 'Dockerfile' -o -name 'Dockerfile.*' -o -name '*.Dockerfile' -o -name 'Containerfile' \) -print | sort)"
if [[ -z "$dockerfiles" ]]; then note "no Dockerfiles found yet"; fi
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  grep -Eiq '^[[:space:]]*FROM[[:space:]]' "$f" || continue
  d="$(rel_dir "$f")"
  if covered_by docker "$d"; then ok "${f#./} (FROM)"; else fail "${f#./}: directory '/$d' not covered by a docker entry"; fi
done <<<"$dockerfiles"

image_re='^[[:space:]]*(-[[:space:]]+)?image:[[:space:]]*["'"'"']?[A-Za-z0-9]'
if [[ -d deploy/base ]]; then
  base_files="$(grep -rlE "$image_re" deploy/base --include='*.yaml' --include='*.yml' 2>/dev/null | sort || true)"
  [[ -z "$base_files" ]] && note "no image: fields under deploy/base yet"
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    d="$(rel_dir "$f")"
    if covered_by docker "$d"; then ok "$f (image:)"; else fail "$f: directory '/$d' not covered by a docker entry"; fi
  done <<<"$base_files"

  echo "== 4. no Kustomize images: transformer in deploy/base"
  while IFS= read -r k; do
    [[ -z "$k" ]] && continue
    if [[ "$(yq '.images // [] | length' "$k")" != "0" ]]; then
      fail "$k uses a Kustomize images: transformer; use plain image: fields (dependency-maintenance §3.1)"
    else
      ok "$k"
    fi
  done < <(find deploy/base -type f \( -name kustomization.yaml -o -name kustomization.yml -o -name Kustomization \) | sort)
else
  note "deploy/base not present yet; image coverage not checked"
fi

echo "== 5. workflow-owned files are not covered by a parsing ecosystem"
n_files="$(jq '(.files // []) | length' <<<"$own_json")"
i=0
while (( i < n_files )); do
  path="$(jq -r ".files[$i].path" <<<"$own_json")"
  d="$(rel_dir "$path")"
  bad=""
  while IFS= read -r eco; do
    [[ -z "$eco" ]] && continue
    if covered_by "$eco" "$d"; then bad+="$eco "; fi
  done < <(jq -r ".files[$i].\"not-covered-by\" // [] | .[]" <<<"$own_json")
  if [[ -n "$bad" ]]; then fail "$path is covered by Dependabot ecosystem(s): $bad"; else ok "$path"; fi
  i=$((i + 1))
done

echo "== 6. compose files have a docker-compose entry"
compose="$(find . -maxdepth 2 \( -path ./node_modules -o -path ./.git \) -prune -o -type f \( -name 'compose*.y*ml' -o -name 'docker-compose*.y*ml' \) -print | sort)"
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  if covered_by docker-compose "$(rel_dir "$f")"; then ok "$f"; else fail "${f#./} exists but no docker-compose entry covers it"; fi
done <<<"$compose"
[[ -z "$compose" ]] && ok "no compose file"

echo "== 7. Quarkus platform property and BOM invariants"
poms="$(find . \( "${prune[@]}" \) -prune -o -type f -name pom.xml -print | sort)"
while IFS= read -r p; do
  [[ -z "$p" ]] && continue
  if [[ "$p" != "./pom.xml" ]] && grep -q '<quarkus.platform.version>' "$p"; then
    fail "${p#./} redefines quarkus.platform.version (only the parent POM may)"
  fi
  if grep -q '<artifactId>quarkus-langchain4j-bom</artifactId>' "$p" \
     && grep -B2 '<artifactId>quarkus-langchain4j-bom</artifactId>' "$p" | grep -q 'io.quarkiverse.langchain4j'; then
    fail "${p#./} imports the standalone io.quarkiverse.langchain4j BOM; use io.quarkus.platform:quarkus-langchain4j-bom"
  fi
done <<<"$poms"
if [[ -z "$poms" ]]; then note "no pom.xml yet"; else ok "checked $(grep -c . <<<"$poms") POM(s)"; fi

echo "== 8. image references outside Dependabot coverage are declared blind spots"
# Kustomize patches embed manifests as strings and Helm values use repository/tag maps; the
# docker ecosystem parses neither. Each such file must be listed under `blind-spots` with an owner.
declared_blind="$(jq -r '(."blind-spots" // [])[] | .path' <<<"$own_json")"
if [[ -d deploy ]]; then
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    d="$(rel_dir "$f")"
    covered_by docker "$d" && continue
    if grep -Fxq "$f" <<<"$declared_blind"; then
      note "$f: declared blind spot ($(jq -r --arg p "$f" '."blind-spots"[] | select(.path == $p) | .owner' <<<"$own_json"))"
    else
      fail "$f has image references that no engine updates; cover it with a docker entry or declare it under blind-spots in upgrade-ownership.yml"
    fi
  done < <(grep -rlE "$image_re|^[[:space:]]*(repository|tag):[[:space:]]" deploy --include='*.yaml' --include='*.yml' 2>/dev/null | sort || true)
fi
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  [[ -e "$f" ]] || note "blind-spots entry $f no longer exists; remove it"
done <<<"$declared_blind"

echo
if (( errors > 0 )); then
  echo "ownership-check: $errors problem(s)"
  exit 1
fi
echo "ownership-check: OK"
