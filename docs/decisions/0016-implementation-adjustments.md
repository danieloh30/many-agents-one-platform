# 0016 — Adjustments made during implementation

**Status:** Accepted. Supersedes the mechanism (not the policy) of 0002, 0006, 0008 and 0013 where noted.

Implementation evidence contradicted or refined several design details. Each change keeps the original
decision's intent.

| # | Design said | Implemented | Why |
| --- | --- | --- | --- |
| 1 | Hibernate ORM with Panache, JPA `@Version` (0004, architecture §6.3) | Plain JDBC over the Agroal datasource inside JTA transactions; optimistic `version` column checked in `UPDATE … WHERE version = ?`; `SELECT … FOR UPDATE` row locks when folding results | The schemas are small and the SQL (outbox `SKIP LOCKED`, `ON CONFLICT` claims, row locks) is the point of the demo; explicit SQL is easier to read and test than ORM mappings. Flyway still owns the schema. |
| 2 | Quarkus LangChain4j AI services (`@RegisterAiService`, structured POJO returns) (0002) | Agents call the provider's `ChatModel` bean directly through a `ModelGateway`, with an explicit bounded tool loop and our own JSON validation and one repair attempt | Gives exact control over deadlines, per-pod permits, budgets, provider-reported token accounting and deterministic substitution. The OpenAI-compatible model is still configured only through `quarkus.langchain4j.openai.*`. |
| 3 | Mode switch via `chatLanguageModelSupplier` (0013, backlog B-03) | `ModelGateway` chooses the live `ChatModel` or a service-provided `DeterministicScript` at startup from `maop.model.mode` | Resolved the spike without relying on supplier instantiation details; no rebuild needed to switch modes. |
| 4 | CloudEvents Java SDK `cloudevents-core` + `cloudevents-json-jackson` 5.1.0 (0006) | A small in-repo `CloudEvent` record and codec for the NATS binary binding and the JSON format, covered by round-trip tests | The Quarkus 3.40 BOM manages `io.cloudevents:cloudevents-api` at 3.0.0, which would have to be overridden to use SDK 5.x. Overriding a BOM-managed library contradicts the alignment rule; the envelope is small. |
| 5 | Agents call tool-service through the Quarkus REST client configured from the TLS registry (0008) | Agents use `SpiffeHttpClient` (JDK `HttpClient`) built from the spiffe-helper PEM files, rebuilt on rotation; server authenticated by an exact SPIFFE ID match against the trust bundle instead of DNS hostname verification | Client-side SVID rotation without restart and SPIFFE-ID (not hostname) peer authentication, both tested. tool-service itself still uses the Quarkus TLS registry (`quarkus.tls.spiffe.*`, `reload-period`) with `client-auth=required`. |
| 6 | Streams/consumers created by a `nats-setup` Job (deployment §2) | Every service provisions the topology idempotently at startup (ops-console excepted: read-only) | Fresh brokers work regardless of start order; no extra Job. |
| 7 | SPA API types generated from OpenAPI (contracts §4.3) | Hand-written TypeScript types in `web/src/api/types.ts`, validated with zod at the SSE boundary | Avoided a generator toolchain for a single consumer. Contract tests on the Java side remain. |
| 8 | Consumers with `BackOff` lists | No `BackOff`; delayed negative acknowledgements (2 s, then 5 s) and in-progress acknowledgements | JetStream `BackOff` replaces `AckWait` and does not apply to naks. |
| 9 | Evidence worker relies on the model to choose tools | Baseline tool calls per topic are made deterministically; the model may request at most the remaining tool-call budget as follow-ups | Evidence never depends on a live model choosing to call a tool; the scenario stays reliable. |

Consequences: fewer framework features in use (no ORM, no AI-service proxies), more explicit code, and the
same external contracts. Revisit item 4 if a future Quarkus BOM aligns `cloudevents-api` with SDK 5.x.
