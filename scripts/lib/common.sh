# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# Shared helpers for demo.sh. Sourced, never executed. Compatible with bash 3.2 (macOS /bin/bash):
# no associative arrays, no mapfile, no ${var,,}; empty arrays expanded with ${a[@]+"${a[@]}"}.

# ----------------------------------------------------------------------------- output
if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YLW=$'\033[33m'; C_BLU=$'\033[34m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YLW=""; C_BLU=""; C_DIM=""; C_RST=""
fi

log()  { printf '%s==>%s %s\n' "$C_BLU" "$C_RST" "$*" >&2; }
info() { printf '    %s\n' "$*" >&2; }
warn() { printf '%sWARN%s %s\n' "$C_YLW" "$C_RST" "$*" >&2; }
err()  { printf '%sERROR%s %s\n' "$C_RED" "$C_RST" "$*" >&2; }
die()  { err "$*"; exit 1; }
dim()  { printf '%s%s%s\n' "$C_DIM" "$*" "$C_RST" >&2; }

# ----------------------------------------------------------------------------- paths & config
# REPO_ROOT is set by demo.sh before sourcing.
MAOP_STATE_DIR="${REPO_ROOT}/.maop"          # git-ignored: rendered manifests, image refs, logs
MAOP_SERVICES="triage-agent evidence-worker recommendation-agent tool-service ops-console"
MAOP_MANAGED_BY="many-agents-one-platform"
MAOP_PART_OF="maop"
MAOP_IDENTITY_PROBE_IMAGE="docker.io/curlimages/curl:8.23.0"

# Loads .env.local (KEY=value lines) without executing it as shell code and without overriding
# variables already set in the environment. Values are never printed.
load_env_file() {
  local file="${1:-${REPO_ROOT}/.env.local}"
  [[ -f "$file" ]] || return 0
  local line key value
  # shellcheck disable=SC2094  # warn() writes to stderr, not to $file
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    line="${line#export }"
    if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      # Strip one level of matching surrounding quotes.
      if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
        value="${BASH_REMATCH[1]}"
      fi
      if [[ -z "${!key+x}" ]]; then
        printf -v "$key" '%s' "$value"
        # shellcheck disable=SC2163
        export "$key"
      fi
    else
      warn "ignoring malformed line in $(basename "$file") (expected KEY=value)"
    fi
  done < "$file"
}

apply_config_defaults() {
  MAOP_NAMESPACE="${MAOP_NAMESPACE:-maop}"
  MAOP_MODEL_MODE="${MAOP_MODEL_MODE:-live}"
  # OpenAI defaults: an already-exported OPENAI_API_KEY is used when MAOP_MODEL_API_KEY is not set.
  MAOP_MODEL_BASE_URL="${MAOP_MODEL_BASE_URL:-https://api.openai.com/v1/}"
  MAOP_MODEL_NAME="${MAOP_MODEL_NAME:-gpt-4o-mini}"
  MAOP_MODEL_API_KEY="${MAOP_MODEL_API_KEY:-${OPENAI_API_KEY:-}}"
  MAOP_EVIDENCE_MAX_REPLICAS="${MAOP_EVIDENCE_MAX_REPLICAS:-6}"
  MAOP_SPIRE_TRUST_DOMAIN="${MAOP_SPIRE_TRUST_DOMAIN:-}"
  MAOP_SPIRE_CLASS_NAME="${MAOP_SPIRE_CLASS_NAME:-}"
  MAOP_SPIRE_CLUSTER_NAME="${MAOP_SPIRE_CLUSTER_NAME:-}"
  MAOP_SPIFFE_SOCKET_NAME="${MAOP_SPIFFE_SOCKET_NAME:-}"
  MAOP_EXPOSE_OBSERVABILITY="${MAOP_EXPOSE_OBSERVABILITY:-true}"
  MAOP_REGISTRY="${MAOP_REGISTRY:-}"
  MAOP_INGRESS_DOMAIN="${MAOP_INGRESS_DOMAIN:-127.0.0.1.nip.io}"
  MAOP_INGRESS_CLASS="${MAOP_INGRESS_CLASS:-}"
  MAOP_INGRESS_SCHEME="${MAOP_INGRESS_SCHEME:-http}"
  MAOP_BUILD_PLATFORM="${MAOP_BUILD_PLATFORM:-linux/amd64}"
  MAOP_INSECURE_TLS="${MAOP_INSECURE_TLS:-false}"
  MAOP_CONTAINER_ENGINE="${MAOP_CONTAINER_ENGINE:-}"
  case "$MAOP_MODEL_MODE" in live|deterministic) ;; *) die "MAOP_MODEL_MODE must be 'live' or 'deterministic'";; esac
  case "$MAOP_EXPOSE_OBSERVABILITY" in true|false) ;; *) die "MAOP_EXPOSE_OBSERVABILITY must be true or false";; esac
  [[ "$MAOP_EVIDENCE_MAX_REPLICAS" =~ ^[1-9][0-9]*$ ]] || die "MAOP_EVIDENCE_MAX_REPLICAS must be a positive integer"
  [[ "$MAOP_NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$ ]] || die "invalid namespace name: $MAOP_NAMESPACE"
}

# ----------------------------------------------------------------------------- small utilities
have() { command -v "$1" >/dev/null 2>&1; }

# version_ge 24.15.0 24.15 -> true when $1 >= $2 (numeric, dot-separated)
version_ge() {
  local IFS=.
  # shellcheck disable=SC2206
  local a=($1) b=($2) i x y
  for ((i = 0; i < ${#b[@]} || i < ${#a[@]}; i++)); do
    x="${a[i]:-0}"; y="${b[i]:-0}"
    x="${x%%[!0-9]*}"; y="${y%%[!0-9]*}"
    x="${x:-0}"; y="${y:-0}"
    ((10#$x > 10#$y)) && return 0
    ((10#$x < 10#$y)) && return 1
  done
  return 0
}

rand_hex() { printf %s "$(openssl rand -hex "${1:-24}")"; }   # no trailing newline in generated secrets

json_str() { jq -Rn --arg v "$1" '$v'; }

utc_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

new_uuid() {
  if have uuidgen; then uuidgen | tr '[:upper:]' '[:lower:]'; else
    local h; h="$(rand_hex 16)"
    printf '%s-%s-4%s-a%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" "${h:17:3}" "${h:20:12}"
  fi
}

# Private temp dir for this invocation, removed on exit.
MAOP_TMP=""
maop_tmpdir() {
  if [[ -z "$MAOP_TMP" ]]; then
    MAOP_TMP="$(umask 077; mktemp -d "${TMPDIR:-/tmp}/maop.XXXXXX")"
  fi
  printf '%s\n' "$MAOP_TMP"
}

MAOP_CLEANUP_PIDS=""
maop_cleanup() {
  local pid
  for pid in $MAOP_CLEANUP_PIDS; do kill "$pid" >/dev/null 2>&1 || true; done
  [[ -n "$MAOP_TMP" && -d "$MAOP_TMP" ]] && rm -rf "$MAOP_TMP"
  return 0
}
trap maop_cleanup EXIT

# ----------------------------------------------------------------------------- git / image tags
git_image_tag() {
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null || true)"
  [[ -n "$sha" ]] || sha="nogit"
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]]; then
    sha="${sha}-dirty"
  fi
  printf '%s\n' "$sha"
}

# ----------------------------------------------------------------------------- kube CLI
# KC is oc on OpenShift; on generic Kubernetes kubectl if present, else oc (oc embeds kubectl).
select_kube_cli() {
  if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
    have oc || die "oc is required for --platform openshift"
    KC=oc
  elif have kubectl; then
    KC=kubectl
  elif have oc; then
    KC=oc
  else
    die "kubectl or oc is required"
  fi
}

k()  { "$KC" "$@"; }
kn() { "$KC" -n "$MAOP_NAMESPACE" "$@"; }

# Local-only: reads kubeconfig, does not contact the cluster.
kube_context_name() { "$KC" config current-context 2>/dev/null || echo "<none>"; }
kube_server_url()   { "$KC" config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null || echo "<unknown>"; }

# Prints the target and asks for confirmation unless --yes.
confirm_target() {
  local action="$1"
  printf '\n%sTarget for %s%s\n' "$C_YLW" "$action" "$C_RST" >&2
  printf '  context:   %s\n' "$(kube_context_name)" >&2
  printf '  API URL:   %s\n' "$(kube_server_url)" >&2
  printf '  namespace: %s\n' "$MAOP_NAMESPACE" >&2
  printf '  platform:  %s\n\n' "$MAOP_PLATFORM" >&2
  if [[ "${OPT_YES:-false}" == "true" ]]; then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    die "refusing to change the cluster without a terminal; rerun with --yes"
  fi
  local answer
  read -r -p "Proceed? Type 'yes' to continue: " answer
  [[ "$answer" == "yes" ]] || die "aborted"
}

# Namespace exists and is owned by this project? Prints: absent | owned | foreign | unreachable
namespace_ownership() {
  local label errf
  errf="${MAOP_TMP:-/tmp}/ns-ownership.err"
  if ! label="$("$KC" get namespace "$MAOP_NAMESPACE" -o jsonpath='{.metadata.labels.app\.kubernetes\.io/managed-by}' 2>"$errf")"; then
    if grep -q -i -E 'NotFound|not found' "$errf" 2>/dev/null; then echo absent; else echo unreachable; fi
  elif [[ "$label" == "$MAOP_MANAGED_BY" ]]; then
    echo owned
  else
    echo foreign
  fi
}

require_owned_namespace() {
  case "$(namespace_ownership)" in
    owned) ;;
    absent) die "namespace '$MAOP_NAMESPACE' does not exist (run ./demo.sh up first)";;
    foreign) die "namespace '$MAOP_NAMESPACE' exists but is not labelled app.kubernetes.io/managed-by=$MAOP_MANAGED_BY; refusing to touch it";;
    *) die "cannot read namespace '$MAOP_NAMESPACE' (cluster unreachable or not logged in: $(kube_server_url))";;
  esac
}
