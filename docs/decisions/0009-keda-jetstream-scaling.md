# 0009 — KEDA scales evidence workers on JetStream lag

**Status:** Accepted

## Context
Evidence work arrives in bursts. Scaling should follow real backlog. On OpenShift, the Custom
Metrics Autoscaler operator is the supported KEDA distribution but does not list the NATS
JetStream trigger, and it cannot coexist with upstream KEDA.

## Decision
- A KEDA `ScaledObject` with the `nats-jetstream` trigger scales the `evidence-worker` Deployment
  between 1 and 6 replicas on the lag of consumer `evidence-workers`.
- Minimum 1 (no scale to zero) for demo responsiveness; maximum bounded by model rate limits.
- Upstream KEDA from its Helm chart on both profiles. If a cluster already runs the Custom Metrics
  Autoscaler, use the `openshift-cma` overlay with a `prometheus` trigger on the NATS exporter's
  pending-message metric instead of installing a second KEDA.
- Only this Deployment is autoscaled.

## Consequences
- Lag includes in-flight messages, so replicas stay up until work is acknowledged.
- Scale-out latency (poll + HPA + pod start) must be rehearsed on the target cluster.
- Upstream KEDA on OpenShift is community-supported, not vendor-supported.
