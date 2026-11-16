#!/usr/bin/env bash
# Fully local Kubernetes run: a kind cluster (podman or docker) plus a local image registry, then the same
# ./demo.sh up / verify path as any other cluster (KEDA scale-out, SPIRE-issued SVIDs, Prometheus, Grafana).
#
#   scripts/local/kind.sh up        # create cluster + registry (if absent) and deploy with ./demo.sh up
#   scripts/local/kind.sh verify    # ./demo.sh verify against the kind cluster
#   scripts/local/kind.sh env       # print the exports to use ./demo.sh status|open|logs|token yourself
#   scripts/local/kind.sh down      # delete the kind cluster and the registry container
#
# The cluster gets its own kubeconfig (.maop/kind/kubeconfig): your default kubeconfig and context are never
# changed. NetworkPolicies are rendered off on kind (MAOP_NETWORK_POLICIES=false): with kindnet's policy
# enforcement the application pods could not resolve DNS in testing; the root cause was not identified.
# OpenShift (OVN-Kubernetes) enforces the same policies without issues.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.maop/kind"
CLUSTER="maop"
NODE_IMAGE="kindest/node:v1.35.8@sha256:07b2536e30b803ed61d1677a79df6115f798ce64c80f9e22f6ed45afd09323c0"
REGISTRY_IMAGE="docker.io/library/registry:3"
ENGINE="${MAOP_CONTAINER_ENGINE:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}"

log() { printf '\033[1;36m[kind]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[kind]\033[0m %s\n' "$*" >&2; exit 1; }

kind_env() {
  export KUBECONFIG="$STATE/kubeconfig"
  export MAOP_REGISTRY="localhost:5001/maop" MAOP_REGISTRY_TLS_VERIFY=false
  export MAOP_SPIRE_TRUST_DOMAIN="${MAOP_SPIRE_TRUST_DOMAIN:-maop.kind}" MAOP_NETWORK_POLICIES=false
  case "$(uname -m)" in arm64|aarch64) export MAOP_BUILD_PLATFORM=linux/arm64;; *) export MAOP_BUILD_PLATFORM=linux/amd64;; esac
  [ "$(basename "$ENGINE")" = podman ] && export KIND_EXPERIMENTAL_PROVIDER=podman
  return 0
}

ensure_cluster() {
  command -v kind >/dev/null 2>&1 || die "kind is not installed (for example: brew install kind)"
  mkdir -p "$STATE"
  kind_env
  if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    cat > "$STATE/cluster.yaml" <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: maop
containerdConfigPatches:
  - |-
    [plugins."io.containerd.grpc.v1.cri".registry]
      config_path = "/etc/containerd/certs.d"
nodes:
  - role: control-plane
EOF
    log "creating kind cluster '$CLUSTER' ($ENGINE)"
    kind create cluster --config "$STATE/cluster.yaml" --image "$NODE_IMAGE" --kubeconfig "$KUBECONFIG"
  else
    kind get kubeconfig --name "$CLUSTER" > "$KUBECONFIG"
  fi
  if ! "$ENGINE" container inspect kind-registry >/dev/null 2>&1; then
    log "starting local registry on localhost:5001"
    "$ENGINE" run -d --restart=always --name kind-registry -p 127.0.0.1:5001:5000 --network kind "$REGISTRY_IMAGE" >/dev/null
  fi
  # Nodes pull localhost:5001/* from the registry container over the kind network.
  "$ENGINE" exec "$CLUSTER-control-plane" mkdir -p /etc/containerd/certs.d/localhost:5001
  printf '[host."http://kind-registry:5000"]\n' | "$ENGINE" exec -i "$CLUSTER-control-plane" cp /dev/stdin /etc/containerd/certs.d/localhost:5001/hosts.toml
  kubectl wait --for=condition=Ready node --all --timeout=180s >/dev/null
  [ "$(kubectl config current-context)" = "kind-$CLUSTER" ] || die "unexpected context in $KUBECONFIG"
}

case "${1:-}" in
  up)
    ensure_cluster
    log "deploying with ./demo.sh up (platform kubernetes, KEDA + SPIRE charts installed into the kind cluster)"
    "$ROOT/demo.sh" up --platform kubernetes --profile summit --install-platform-components --yes
    ;;
  verify)
    kind_env
    "$ROOT/demo.sh" verify --platform kubernetes
    ;;
  env)
    kind_env
    printf 'export KUBECONFIG=%q MAOP_REGISTRY=%q MAOP_REGISTRY_TLS_VERIFY=false MAOP_SPIRE_TRUST_DOMAIN=%q MAOP_NETWORK_POLICIES=false MAOP_BUILD_PLATFORM=%q\n' \
      "$KUBECONFIG" "$MAOP_REGISTRY" "$MAOP_SPIRE_TRUST_DOMAIN" "$MAOP_BUILD_PLATFORM"
    ;;
  down)
    kind_env
    kind delete cluster --name "$CLUSTER" 2>/dev/null || true
    "$ENGINE" rm -f kind-registry >/dev/null 2>&1 || true
    rm -f "$STATE/kubeconfig"
    log "deleted kind cluster and registry"
    ;;
  *) echo "usage: $0 up|verify|env|down" >&2; exit 2 ;;
esac
