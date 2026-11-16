#!/usr/bin/env bash
# many-agents-one-platform — deployment and demo automation.
# Run ./demo.sh --help for usage. Works with bash 3.2+ (macOS default) and GNU bash.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export REPO_ROOT

# shellcheck source=scripts/lib/common.sh
source "${REPO_ROOT}/scripts/lib/common.sh"
# shellcheck source=scripts/lib/cluster.sh
source "${REPO_ROOT}/scripts/lib/cluster.sh"
# shellcheck source=scripts/deploy/render.sh
source "${REPO_ROOT}/scripts/deploy/render.sh"
# shellcheck source=scripts/deploy/doctor.sh
source "${REPO_ROOT}/scripts/deploy/doctor.sh"
# shellcheck source=scripts/deploy/platform.sh
source "${REPO_ROOT}/scripts/deploy/platform.sh"
# shellcheck source=scripts/deploy/build.sh
source "${REPO_ROOT}/scripts/deploy/build.sh"
# shellcheck source=scripts/deploy/up.sh
source "${REPO_ROOT}/scripts/deploy/up.sh"
# shellcheck source=scripts/deploy/console.sh
source "${REPO_ROOT}/scripts/deploy/console.sh"
# shellcheck source=scripts/deploy/verify.sh
source "${REPO_ROOT}/scripts/deploy/verify.sh"
# shellcheck source=scripts/deploy/ops.sh
source "${REPO_ROOT}/scripts/deploy/ops.sh"
# shellcheck source=deploy/platform/versions.env
source "${REPO_ROOT}/deploy/platform/versions.env"

usage() {
  cat <<'EOF'
Usage: ./demo.sh <command> [options]

Commands
  doctor            Read-only checks: local tools, cluster login, permissions, KEDA/CMA and
                    SPIRE state, storage, namespace ownership, model configuration.
  build             Build jars + SPA, then container images (OpenShift: in-cluster binary
                    builds; kubernetes: podman/docker build + push to MAOP_REGISTRY).
  up                Doctor, platform components (only with --install-platform-components),
                    namespace, secrets, build, apply manifests, wait, smoke test, print URLs.
  status            Workloads, replicas, ScaledObject/HPA, routes, platform state, last runs.
  verify            Collect real evidence for every demo claim into artifacts/verify-<ts>/.
                    Use --quick for the smoke subset (success, trace, denial, identity).
  open              Print (and on macOS open) the console, Jaeger and Grafana URLs.
  logs [service]    Follow logs of one service, or of all demo pods with prefixes.
  reset             Clear demonstration state through the console API (runs, audit, queues).
  token             Print the presenter token (the only command that prints it).
  down              Remove the demo's application resources (keeps PVCs and Secrets).
  render            Render the manifests locally (no cluster access) to stdout or --output.
  help              Show this help.

Options
  --platform openshift|kubernetes   Target platform (default: openshift; env MAOP_PLATFORM).
  --namespace NAME                  Application namespace (default: maop; env MAOP_NAMESPACE).
  --profile summit|dev              summit: 1 warm evidence worker, max MAOP_EVIDENCE_MAX_REPLICAS
                                    (default 6); dev: smaller requests (default: summit).
  --install-platform-components     up: allow installing KEDA and/or SPIRE (cluster-scoped).
  --skip-build                      up: deploy the images from the last build.
  --skip-model-probe                doctor/up: skip the request to ${MAOP_MODEL_BASE_URL}models.
  --yes, -y                         Do not ask for confirmation before changing the cluster.
  --quick                           verify: smoke subset only.
  --delete-data                     down: also delete PVCs and Secrets.
  --delete-namespace                down: also delete the namespace.
  --uninstall-platform-components   down: also uninstall platform components that THIS project
                                    installed (recorded in ConfigMap maop-installed-components).
  --scaler keda|cma                 render: which ScaledObject trigger to render (default keda).
  --trust-domain TD                 render: trust domain to render (default maop.example).
  --output FILE                     render: write manifests to FILE instead of stdout.
  -h, --help                        Show this help.

Configuration (.env.local, git-ignored; see .env.example)
  MAOP_MODEL_MODE (live|deterministic), MAOP_MODEL_BASE_URL, MAOP_MODEL_NAME, MAOP_MODEL_API_KEY,
  MAOP_NAMESPACE, MAOP_EVIDENCE_MAX_REPLICAS, MAOP_SPIRE_TRUST_DOMAIN, MAOP_EXPOSE_OBSERVABILITY,
  MAOP_REGISTRY (kubernetes), MAOP_INGRESS_DOMAIN (kubernetes). Secrets are never printed.

Examples
  ./demo.sh doctor --platform openshift --install-platform-components
  ./demo.sh up --platform openshift --profile summit --install-platform-components
  ./demo.sh verify
  ./demo.sh up --platform kubernetes --namespace maop-dev --profile dev
  ./demo.sh down --yes
EOF
}

main() {
  local cmd="${1:-help}"
  [[ $# -gt 0 ]] && shift

  MAOP_PLATFORM="${MAOP_PLATFORM:-openshift}"
  MAOP_PROFILE="${MAOP_PROFILE:-summit}"
  OPT_YES=false; OPT_INSTALL_PLATFORM=false; OPT_SKIP_BUILD=false; OPT_SKIP_MODEL_PROBE=false
  OPT_QUICK=false; OPT_DELETE_DATA=false; OPT_DELETE_NAMESPACE=false; OPT_UNINSTALL_PLATFORM=false
  OPT_SCALER=keda; OPT_TRUST_DOMAIN=""; OPT_OUTPUT=""; OPT_NAMESPACE=""
  local positional=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --platform) MAOP_PLATFORM="${2:?--platform needs a value}"; shift 2;;
      --platform=*) MAOP_PLATFORM="${1#*=}"; shift;;
      --namespace|-n) OPT_NAMESPACE="${2:?--namespace needs a value}"; shift 2;;
      --namespace=*) OPT_NAMESPACE="${1#*=}"; shift;;
      --profile) MAOP_PROFILE="${2:?--profile needs a value}"; shift 2;;
      --profile=*) MAOP_PROFILE="${1#*=}"; shift;;
      --install-platform-components) OPT_INSTALL_PLATFORM=true; shift;;
      --skip-build) OPT_SKIP_BUILD=true; shift;;
      --skip-model-probe) OPT_SKIP_MODEL_PROBE=true; shift;;
      --yes|-y) OPT_YES=true; shift;;
      --quick) OPT_QUICK=true; shift;;
      --delete-data) OPT_DELETE_DATA=true; shift;;
      --delete-namespace) OPT_DELETE_NAMESPACE=true; shift;;
      --uninstall-platform-components) OPT_UNINSTALL_PLATFORM=true; shift;;
      --scaler) OPT_SCALER="${2:?--scaler needs a value}"; shift 2;;
      --trust-domain) OPT_TRUST_DOMAIN="${2:?--trust-domain needs a value}"; shift 2;;
      --output|-o) OPT_OUTPUT="${2:?--output needs a value}"; shift 2;;
      -h|--help) usage; exit 0;;
      --) shift; while [[ $# -gt 0 ]]; do positional+=("$1"); shift; done;;
      -*) die "unknown option: $1 (see ./demo.sh --help)";;
      *) positional+=("$1"); shift;;
    esac
  done

  case "$MAOP_PLATFORM" in openshift|kubernetes) ;; *) die "--platform must be openshift or kubernetes";; esac
  case "$MAOP_PROFILE" in summit|dev) ;; *) die "--profile must be summit or dev";; esac
  case "$OPT_SCALER" in keda|cma) ;; *) die "--scaler must be keda or cma";; esac

  load_env_file "${REPO_ROOT}/.env.local"
  [[ -n "$OPT_NAMESPACE" ]] && MAOP_NAMESPACE="$OPT_NAMESPACE"
  apply_config_defaults
  export MAOP_PLATFORM MAOP_NAMESPACE MAOP_PROFILE
  mkdir -p "$MAOP_STATE_DIR"
  maop_tmpdir >/dev/null   # create the private temp dir in this shell (removed on exit)

  case "$cmd" in
    help|-h|--help) usage;;
    render) cmd_render;;
    doctor) select_kube_cli; cmd_doctor;;
    build) select_kube_cli; cmd_build;;
    up) select_kube_cli; cmd_up;;
    status) select_kube_cli; cmd_status;;
    verify) select_kube_cli; cmd_verify;;
    open) select_kube_cli; cmd_open;;
    logs) select_kube_cli; cmd_logs ${positional[@]+"${positional[@]}"};;
    reset) select_kube_cli; cmd_reset;;
    token) select_kube_cli; cmd_token;;
    down) select_kube_cli; cmd_down;;
    *) err "unknown command: $cmd"; usage >&2; exit 2;;
  esac
}

main "$@"
