# Architecture decision records

Short records of decisions that shape the system. Format: status, context, decision, consequences.
A decision is changed by adding a new record that supersedes the old one, not by editing history.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-service-boundaries.md) | Five services; triage is the single writer of run state; workers are stateless | Accepted |
| [0002](0002-java-25-quarkus-lts.md) | Java 25 on the Quarkus LTS platform; LangChain4j from the platform member BOM | Accepted |
| [0003](0003-explicit-durable-orchestration.md) | Explicit durable orchestration instead of an in-process agent graph | Accepted |
| [0004](0004-outbox-inbox.md) | Transactional outbox with in-process relay; inbox for durable deduplication | Accepted |
| [0005](0005-jnats-direct.md) | Use the `jnats` client directly behind a thin adapter | Accepted |
| [0006](0006-cloudevents-nats-binary.md) | CloudEvents in binary mode on NATS, mapped by hand | Accepted |
| [0007](0007-opa-pep-at-tool-service.md) | OPA sidecar as decision point; tool-service as fail-closed enforcement point | Accepted |
| [0008](0008-spiffe-helper-tls-registry.md) | SPIFFE mTLS through spiffe-helper and the Quarkus TLS registry, scoped to the tool API | Accepted |
| [0009](0009-keda-jetstream-scaling.md) | KEDA NATS JetStream scaler for evidence agents; upstream KEDA on OpenShift | Accepted |
| [0010](0010-observability-pipeline.md) | Traces over OTLP through the Collector to Jaeger; metrics through Micrometer and Prometheus | Accepted |
| [0011](0011-kustomize-helm-openshift-builds.md) | Kustomize for the application, Helm for cluster platforms, in-cluster binary builds on OpenShift | Accepted |
| [0012](0012-agent-substrate-optional-future.md) | Agent Substrate is an optional future execution integration | Accepted |
| [0013](0013-model-modes.md) | Live model by default; deterministic substitute is explicit and labelled; no fallback | Accepted |
| [0014](0014-dependency-ownership.md) | Dependabot plus one scheduled workflow with disjoint ownership | Accepted |
| [0015](0015-console-bff-serves-spa.md) | A separate console backend serves the SPA and holds the only Kubernetes API access | Accepted |
| [0016](0016-implementation-adjustments.md) | Adjustments made during implementation (JDBC, direct ChatModel, in-repo CloudEvents codec, SPIFFE HTTP client) | Accepted |
| [0017](0017-langchain4j-agent-interfaces.md) | Agents as LangChain4j `@Agent` interfaces behind the model gateway | Accepted |
| [0018](0018-sandboxed-tool-class.md) | A sandboxed tool class with a simulated executor; scenario 5 | Accepted |
