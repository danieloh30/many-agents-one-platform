# 0010 — Observability pipeline

**Status:** Accepted

## Context
Required: OpenTelemetry, a Collector, Jaeger, Prometheus, Grafana, and connected traces across
synchronous calls and messages. In the pinned Quarkus, OTel traces are stable; OTel metrics/logs
and the Micrometer-to-OTLP bridge are preview. LangChain4j metrics are Micrometer-based.

## Decision
- Traces: Quarkus OpenTelemetry → OTLP → Collector → Jaeger v2 (memory storage).
- Metrics: Micrometer with the Prometheus registry, scraped by Prometheus. The Collector adds
  span-derived request/error/duration metrics through the span-metrics connector.
- One trace per run: context is carried in HTTP headers, NATS headers, and the outbox row.
- Consumer spans are children of the producer context (not links), so a run reads as one trace.
- Prompts and completions are excluded from telemetry.
- Jaeger, Prometheus, Grafana, and the Collector run as plain Deployments in the application namespace.

## Consequences
- No preview features in the telemetry path.
- Independent of cluster monitoring operators; identical on kind and OpenShift.
- Long-running runs produce long traces; acceptable for the demo.
- Not a production monitoring architecture (single replicas, ephemeral storage).
