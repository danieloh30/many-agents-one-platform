#!/usr/bin/env bash
# Fail if any POM, Dockerfile, workflow, or toolchain file references a JDK below the minimum.
# Rule source: CLAUDE.md "Java 25 or newer".
#
# Checks:
#   pom.xml        <maven.compiler.release|source|target>, <java.version>, <release> (compiler plugin)
#   Dockerfiles    FROM images with openjdk-<N>, eclipse-temurin:<N>, openjdk:<N>, jdk<N>/jre<N> tags
#   workflows      java-version: <N>
#   .sdkmanrc      java=<N>...
#   any            --enable-preview (forbidden)
# Env: MIN_JAVA (default 25)
set -o errexit -o nounset -o pipefail
# shellcheck source=scripts/maintenance/lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MIN_JAVA="${MIN_JAVA:-25}"
cd "$REPO_ROOT"
errors=0
checked=0

check() { # file line-no found-version context
  checked=$((checked + 1))
  if [[ "$3" =~ ^[0-9]+$ ]] && (( $3 < MIN_JAVA )); then
    printf 'FAIL: %s:%s: Java %s < %s: %s\n' "$1" "$2" "$3" "$MIN_JAVA" "$4"
    errors=$((errors + 1))
  fi
}

# Emit "file<TAB>line<TAB>version<TAB>text" for every match of an extended regex whose first
# captured number is the Java version (extracted with sed).
scan() { # sed-extract-expr grep-regex files...
  local expr="$1" re="$2"; shift 2
  [[ $# -gt 0 ]] || return 0
  grep -HnE "$re" "$@" 2>/dev/null | while IFS= read -r hit; do
    local file="${hit%%:*}" rest="${hit#*:}"
    local line="${rest%%:*}" text="${rest#*:}"
    local v
    v="$(printf '%s' "$text" | sed -nE "$expr")"
    printf '%s\t%s\t%s\t%s\n' "$file" "$line" "$v" "$(printf '%s' "$text" | sed -E 's/^[[:space:]]+//')"
  done
}

prune=(-path ./node_modules -o -path '*/node_modules' -o -path '*/target' -o -path ./.git)
list() { find . \( "${prune[@]}" \) -prune -o -type f \( "$@" \) -print | sed 's#^\./##' | sort; }

poms=(); while IFS= read -r f; do poms+=("$f"); done < <(list -name pom.xml)
dockerfiles=(); while IFS= read -r f; do dockerfiles+=("$f"); done < <(list -name 'Dockerfile' -o -name 'Dockerfile.*' -o -name '*.Dockerfile' -o -name Containerfile)
workflows=(); while IFS= read -r f; do workflows+=("$f"); done < <(find .github/workflows -type f \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | sort)

results="$(
  {
    if [[ ${#poms[@]} -gt 0 ]]; then
      scan 's/.*<(maven\.compiler\.(release|source|target)|java\.version|release)>[[:space:]]*(1\.)?([0-9]+).*/\4/p' \
        '<(maven\.compiler\.(release|source|target)|java\.version|release)>[[:space:]]*(1\.)?[0-9]+' "${poms[@]}"
    fi
    if [[ ${#dockerfiles[@]} -gt 0 ]]; then
      scan 's/.*(openjdk-|openjdk:|eclipse-temurin:|temurin-|jdk-?|jre-?)([0-9]+).*/\2/p' \
        '^[[:space:]]*FROM[[:space:]].*(openjdk-|openjdk:|eclipse-temurin:|temurin-|jdk-?|jre-?)[0-9]+' "${dockerfiles[@]}"
    fi
    if [[ ${#workflows[@]} -gt 0 ]]; then
      scan "s/.*java-version:[[:space:]]*[\"']?([0-9]+).*/\\1/p" \
        "java-version:[[:space:]]*[\"']?[0-9]+" "${workflows[@]}"
    fi
    if [[ -f .sdkmanrc ]]; then
      scan 's/^java=([0-9]+).*/\1/p' '^java=[0-9]+' .sdkmanrc
    fi
  }
)"

while IFS=$'\t' read -r file line v text; do
  [[ -z "${file:-}" ]] && continue
  check "$file" "$line" "$v" "$text"
done <<<"$results"

# --enable-preview is forbidden (CLAUDE.md rule 1).
preview_files=("${poms[@]+"${poms[@]}"}" "${dockerfiles[@]+"${dockerfiles[@]}"}" "${workflows[@]+"${workflows[@]}"}")
if [[ ${#preview_files[@]} -gt 0 ]]; then
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    case "$hit" in .github/workflows/ci.yml:*|*java-version-guard*) continue ;; esac
    printf 'FAIL: %s: --enable-preview is not allowed\n' "$hit"
    errors=$((errors + 1))
  done < <(grep -HnE -- '--enable-preview' "${preview_files[@]}" 2>/dev/null || true)
fi

echo "java-version-guard: checked $checked Java version reference(s) in ${#poms[@]} POM(s), ${#dockerfiles[@]} Dockerfile(s), ${#workflows[@]} workflow(s); minimum $MIN_JAVA"
if (( errors > 0 )); then
  echo "java-version-guard: $errors violation(s)"
  exit 1
fi
echo "java-version-guard: OK"
