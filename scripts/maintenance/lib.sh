# shellcheck shell=bash
# Shared helpers for scripts/maintenance/*. Source, do not execute.
#
# Overridable inputs (environment):
#   REPO_ROOT            repository root (default: two levels above this file)
#   POM_FILE             parent POM                     (default: $REPO_ROOT/pom.xml)
#   VERSIONS_ENV         Helm chart pins                (default: $REPO_ROOT/deploy/platform/versions.env)
#   WRAPPER_PROPERTIES   Maven Wrapper properties       (default: $REPO_ROOT/.mvn/wrapper/maven-wrapper.properties)
#   DEPENDABOT_FILE      Dependabot config              (default: $REPO_ROOT/.github/dependabot.yml)
#   OWNERSHIP_FILE       upgrade ownership              (default: $REPO_ROOT/.github/upgrade-ownership.yml)
#   MAINT_OUT_DIR        where JSON/Markdown results go (default: $RUNNER_TEMP/maintenance in
#                        GitHub Actions, else $REPO_ROOT/target/maintenance)
#   ALLOW_PRERELEASE     "true" to consider pre-releases (default: false)
#   MAVEN_CENTRAL        Maven repository base URL      (default: https://repo1.maven.org/maven2)
#   QUARKUS_REGISTRY     Quarkus registry base URL      (default: https://registry.quarkus.io)

set -o errexit -o nounset -o pipefail

_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "$_lib_dir/../.." && pwd)}"
POM_FILE="${POM_FILE:-$REPO_ROOT/pom.xml}"
VERSIONS_ENV="${VERSIONS_ENV:-$REPO_ROOT/deploy/platform/versions.env}"
WRAPPER_PROPERTIES="${WRAPPER_PROPERTIES:-$REPO_ROOT/.mvn/wrapper/maven-wrapper.properties}"
DEPENDABOT_FILE="${DEPENDABOT_FILE:-$REPO_ROOT/.github/dependabot.yml}"
OWNERSHIP_FILE="${OWNERSHIP_FILE:-$REPO_ROOT/.github/upgrade-ownership.yml}"
MAINT_OUT_DIR="${MAINT_OUT_DIR:-${RUNNER_TEMP:+$RUNNER_TEMP/maintenance}}"
MAINT_OUT_DIR="${MAINT_OUT_DIR:-$REPO_ROOT/target/maintenance}"
ALLOW_PRERELEASE="${ALLOW_PRERELEASE:-false}"
MAVEN_CENTRAL="${MAVEN_CENTRAL:-https://repo1.maven.org/maven2}"
QUARKUS_REGISTRY="${QUARKUS_REGISTRY:-https://registry.quarkus.io}"
export REPO_ROOT POM_FILE VERSIONS_ENV WRAPPER_PROPERTIES DEPENDABOT_FILE OWNERSHIP_FILE MAINT_OUT_DIR

mkdir -p "$MAINT_OUT_DIR"

log()  { printf '%s\n' "$*" >&2; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

need() {
  local t
  for t in "$@"; do
    command -v "$t" >/dev/null 2>&1 || die "required tool not found: $t"
  done
}

# curl with retries; never prints credentials (none are used).
fetch() {
  curl --fail --silent --show-error --location --retry 3 --retry-delay 2 --max-time 60 "$@"
}

# Write key=value to $GITHUB_OUTPUT when running in Actions (no-op locally).
gh_output() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$1" "$2" >>"$GITHUB_OUTPUT"
  fi
}

# Append a Markdown file to the job summary when running in Actions.
gh_summary_file() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" && -f "$1" ]]; then
    cat "$1" >>"$GITHUB_STEP_SUMMARY"
  fi
}

# True (0) when a version string looks like a stable release.
# Rejects Maven/Quarkus/semver pre-release markers: Alpha, Beta, CR, RC, Mn, milestone,
# SNAPSHOT, preview, EA, dev, and any semver "-prerelease" suffix with letters.
is_stable() {
  local v="$1"
  if printf '%s' "$v" | grep -Eiq '(alpha|beta|snapshot|milestone|preview|[.-]cr[0-9]*$|[.-]cr[0-9]*[.-]|rc[0-9]*|[.-]m[0-9]+$|[.-]ea|[.-]dev)'; then
    return 1
  fi
  return 0
}

# Filter stdin versions: drop pre-releases unless ALLOW_PRERELEASE=true.
filter_stable() {
  local v
  while IFS= read -r v; do
    [[ -z "$v" ]] && continue
    if [[ "$ALLOW_PRERELEASE" == "true" ]] || is_stable "$v"; then
      printf '%s\n' "$v"
    fi
  done
}

# Highest version from stdin (version sort).
max_version() { sort -V | tail -n 1; }

# 0 when $1 > $2 by version sort.
version_gt() {
  [[ "$1" != "$2" ]] && [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

# Numeric component N (1-based) of a dotted version, stripping a leading "v".
vpart() {
  local v="${1#v}" n="$2"
  printf '%s' "$v" | awk -F'[.-]' -v n="$n" '{ print ($n ~ /^[0-9]+$/) ? $n : "" }'
}

# Classify a bump: major | minor | patch | none | unknown.
bump_level() {
  local from="${1#v}" to="${2#v}" a b c x y z
  [[ "$from" == "$to" ]] && { echo none; return; }
  a=$(vpart "$from" 1); b=$(vpart "$from" 2); c=$(vpart "$from" 3)
  x=$(vpart "$to" 1);   y=$(vpart "$to" 2);   z=$(vpart "$to" 3)
  [[ -z "$a" || -z "$x" ]] && { echo unknown; return; }
  if [[ "$a" != "$x" ]]; then echo major
  elif [[ "${b:-0}" != "${y:-0}" ]]; then echo minor
  elif [[ "${c:-0}" != "${z:-0}" ]]; then echo patch
  else echo patch   # differs only beyond the third component (e.g. 3.40.1.2 -> 3.40.1.3)
  fi
}

# Stream (major.minor) of a version.
stream_of() { printf '%s.%s' "$(vpart "$1" 1)" "$(vpart "$1" 2)"; }

# Read a property from the parent POM without running Maven (yq XML mode).
pom_property() {
  local prop="$1" value=""
  [[ -f "$POM_FILE" ]] || die "POM not found: $POM_FILE (set POM_FILE)"
  value="$(yq -p xml -o y ".project.properties[\"$prop\"] // \"\"" "$POM_FILE" 2>/dev/null || true)"
  if [[ -z "$value" && -x "$REPO_ROOT/mvnw" && "$POM_FILE" == "$REPO_ROOT/pom.xml" ]]; then
    value="$("$REPO_ROOT/mvnw" -B -q -N help:evaluate -DforceStdout -Dexpression="$prop" 2>/dev/null || true)"
  fi
  printf '%s' "$value"
}

# All versions listed in maven-metadata.xml for group:artifact.
maven_versions() {
  local ga="$1" g a
  g="${ga%%:*}"; a="${ga#*:}"
  fetch "$MAVEN_CENTRAL/${g//.//}/$a/maven-metadata.xml" \
    | yq -p xml -o y '.metadata.versioning.versions.version | (select(kind == "seq") | .[]) // .'
}

# Latest stable (or any, with ALLOW_PRERELEASE) version for group:artifact.
# Versions not starting with a digit (e.g. io.nats:jnats "v0.7.2") are legacy oddities and skipped.
maven_latest() {
  maven_versions "$1" | grep -E '^[0-9]' | filter_stable | max_version
}

# Read KEY=VALUE pins without sourcing (no code execution from data files).
env_value() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || return 0
  { grep -E "^[[:space:]]*${key}=" "$file" || true; } | tail -n 1 | sed -E "s/^[[:space:]]*${key}=//; s/^[\"']//; s/[\"'][[:space:]]*$//; s/[[:space:]]+#.*$//"
}

# Escape a string for a Markdown table cell.
md_cell() { printf '%s' "$1" | sed 's/|/\\|/g'; }
