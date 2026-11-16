# 0011 — Packaging, deployment, and builds

**Status:** Accepted

## Context
One repository must deploy to local Kubernetes and to OpenShift, be maintainable by Dependabot,
and work from a private repository without an external registry.

## Decision
- Hand-written Kustomize base and overlays (`kind`, `openshift`, `openshift-cma`) for everything in the namespace.
- Helm only for cluster-scoped platforms with maintained charts (KEDA, SPIRE), pinned in one file.
- OpenShift: `BuildConfig` binary Docker builds into `ImageStream`s; kind: local build and `kind load`.
- Application workloads fit `restricted-v2`; no custom SCC for them.
- Scripts are idempotent; only two of them change a cluster and both require confirmation.

## Consequences
- Manifests are explicit and reviewable; no generated YAML to reconcile.
- Image tags sit in plain `image:` fields that Dependabot can update.
- Chart versions are maintained by the scheduled workflow.
