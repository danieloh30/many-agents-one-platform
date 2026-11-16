# 0012 — Agent Substrate as an optional future execution integration

**Status:** Accepted (integration itself: not planned for the core demo)

## Context
Agent Substrate (`github.com/agent-substrate/substrate`, site `ate.dev`) is a Kubernetes-based
runtime for sandboxed, stateful, suspendable agent *actors*. Facts checked on 2026-11-15:

- **Release:** v1.0.0 (2026-11-05), with a stated public API stability commitment.
- **CNCF status:** Sandbox — the TOC vote passed; onboarding was still in progress at check time.
  Re-check the CNCF landscape before stating this publicly.
- **Model:** many actors (addressed as `<atespace>/<name>`) are multiplexed onto a pool of warm
  workers; idle actors are snapshotted to object storage and resumed on demand through its router.
  Actor, template, and policy records live in Substrate's own API server backed by PostgreSQL, not
  as Kubernetes objects. Kubernetes CRDs: `WorkerPool`, `SandboxConfig`, `CSIDriverConfig`.
- **Prerequisites:** a recent Kubernetes (latest stable and previous minor); the feature gates
  `ClusterTrustBundle`, `ClusterTrustBundleProjection`, and `PodCertificateRequest`; PostgreSQL;
  S3- or GCS-compatible object storage; a sandbox runtime — bundled gVisor or Kata/Cloud Hypervisor
  microVMs (microVMs need `/dev/kvm`); privileged node components. Installed with its own
  installer (`ate-setup`), no Helm chart.
- **Autoscaling:** `WorkerPool` replicas are static. It exposes a scale subresource, but autoscaling
  is an open design topic and **there is no documented KEDA integration**.
- **OpenShift:** no documentation; the target OpenShift version's Kubernetes is older than the
  project's supported window and the feature gates are not enabled by default.
- It is a different project from `kubernetes-sigs/agent-sandbox`.

## Decision
- Agent Substrate is **not** part of the core demo and nothing in the core depends on it.
- It is documented as a possible future execution target for *selected* sandboxed workloads only —
  the natural candidate is a future tool that runs untrusted or model-generated code (for example a
  log-analysis script) and benefits from isolation and suspend/resume.
- If integrated later, it sits **behind tool-service**: tool-service remains the enforcement point
  (identity, OPA, idempotency, audit) and would dispatch an approved execution to a Substrate actor.
  Run state stays in PostgreSQL; an actor snapshot is a runtime artefact, never business state.
- KEDA in this design scales the `evidence-worker` Deployment. It does **not** manage Substrate
  actors or worker pools, and the documentation must not suggest that it does.
- Any future spike targets kind or a cluster that meets the prerequisites, not the demo's OpenShift cluster.

## Consequences
- The core demo has no dependency on alpha feature gates, sandbox runtimes, or object storage.
- The enforcement and durability model is already compatible with adding a sandboxed executor later.
- This record must be re-validated before any integration work: the project is young and changing.
