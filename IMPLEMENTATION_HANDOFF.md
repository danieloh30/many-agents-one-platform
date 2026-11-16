# Implementation handoff

The design phase is complete. This file tells the implementing model what to build, in what
order, and how each item is accepted. No application code exists yet.

## 1. Read first, in this order

1. `CLAUDE.md` — conventions, Java 25 rule, verification commands, attribution rules.
2. `docs/architecture.md` — services, state machine, reliability model, sequences.
3. `docs/contracts.md` — normative REST and event contracts.
4. `docs/security.md` — identity, policy, enforcement flow.
5. `docs/versions.md` — the pinned version set (do not substitute versions).
6. `docs/deployment.md` — manifests, profiles, cluster-verification items.
7. `docs/ui-design.md` — the console.
8. `docs/dependency-maintenance.md` — automation to build in the final milestone.
9. `docs/decisions/` — why; consult before changing a decision.
10. `docs/demo-runbook.md` — what the finished system must be able to do on stage.

## 2. Ground rules

- **Do not change a decision silently.** If a decision cannot be implemented as written, stop,
  write a superseding ADR in `docs/decisions/`, update the affected docs in the same commit, and
  note it under §6 of this file.
- **Do not invent APIs or configuration properties.** Every Quarkus, LangChain4j, NATS, KEDA,
  SPIRE, OPA, and OpenShift name used in the docs was checked against official sources; anything
  else must be verified in official documentation or source before use.
- **Contracts first.** Write the OpenAPI and JSON Schema files from `docs/contracts.md` before the
  code that uses them.
- **Never** implement real remediation, silent model fallback, fabricated metrics, or placeholder
  "OK" statuses.
- **No cluster mutation** without the user's explicit instruction; work on kind until told otherwise.
- Keep `README.md` and the SPA free of any event, session, or date references.
- Commit in small steps per backlog item; follow the attribution rules in `CLAUDE.md`.

## 3. Target repository layout

```
pom.xml                      # parent: Java 25, quarkus.platform.version, module list
.mvn/ mvnw                   # Maven Wrapper
maop-common/                 # contracts, CloudEvents mapping, JetStream adapter, tracing, outbox/inbox support
triage-agent/  evidence-worker/  recommendation-agent/  tool-service/  ops-console/
web/                         # React + TypeScript + Vite SPA
contracts/openapi/  contracts/events/
policy/maop/                 # Rego, data, tests
deploy/                      # see docs/deployment.md §1
scripts/  Makefile  compose.dev.yaml
.github/                     # workflows, dependabot.yml, upgrade-ownership.yml
docs/
```

Java package root: `io.github.danieloh30.maop`. Maven `groupId`: `io.github.danieloh30.maop`.

## 4. Ordered backlog

Each item lists acceptance criteria (AC). An item is done only when its AC pass in CI or by the
stated command. Milestones are ordered; items inside a milestone may be parallelised where they
do not touch the same module.

### M0 — Foundations

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-01 | Repository skeleton: parent POM, Maven Wrapper, modules, `.editorconfig`, `.gitignore`, `Makefile`, `.sdkmanrc`, `.nvmrc` | `./mvnw -B -q validate` passes on JDK 25. `maven.compiler.release` is 25. An enforcer rule fails the build on JDK < 25. One `quarkus.platform.version` property drives BOM, LangChain4j member BOM, and plugin. |
| B-02 | Contract files: OpenAPI for console, triage, and tool APIs; JSON Schemas and golden samples for every event in contracts §3.4 | Lint passes; each schema has ≥ 1 golden sample; `docs/contracts.md` and files agree (reviewed checklist in the PR). |
| B-03 | **Spike:** model mode selection with `@RegisterAiService` on the pinned extension | A test proves `maop.model.mode=deterministic` routes an AI service to `ScriptedChatModel` and `live` routes to the OpenAI-compatible model, switched by runtime configuration without rebuild. Preferred approach: `chatLanguageModelSupplier` with a supplier that selects by configuration. If that cannot be done without rebuild, record the working alternative in an ADR superseding 0013's mechanism (not its policy). |
| B-04 | **Spike:** `jnats` + virtual threads + JetStream semantics in a Quarkus test with a real NATS container | Tests demonstrate: pull consume, in-progress ack extends the deadline, nak-with-delay redelivers after the delay, `MaxDeliver` advisory is received, duplicate `Nats-Msg-Id` is rejected inside the window, work-queue message is deleted on ack, oldest-message timestamp for a subject can be read. Any behaviour that differs from architecture §6 is recorded and the docs corrected. |
| B-05 | CI skeleton (`ci.yml`) with `java`, `web`, `contracts`, `policy`, `manifests` jobs and the `compat-gate` aggregator | Workflow green on an empty skeleton; actions pinned by SHA. |

### M1 — Shared plumbing (`maop-common`)

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-06 | Contract records, enums, canonical JSON (JCS) and `actionDigest` | Golden-file round-trip tests for every type; digest test vectors. |
| B-07 | CloudEvents ⇄ NATS binary-mode mapping with extensions | Round-trip tests; headers match contracts §3.1 exactly; structured-mode serialisation for DLQ/SSE. |
| B-08 | JetStream adapter: provisioning, publish with `Nats-Msg-Id`, pull subscription loop on virtual threads, ack/nak/in-progress, consumer info, graceful shutdown | Integration tests with NATS container, including shutdown draining within the grace period. |
| B-09 | Trace propagation: inject/extract on NATS headers; restore from stored `traceparent` | Test asserts one trace ID across publish → consume → publish, using an in-memory span exporter. |
| B-10 | Outbox and inbox support (entities, relay, retention jobs, metrics) | Tests: crash between commit and publish still publishes after restart; duplicate event is ignored; two relay instances do not double-publish (`SKIP LOCKED`). |

### M2 — Tool service, policy, identity

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-11 | Rego policy, data, and tests | `opa test` covers every row of security §4.3 as a decision table; coverage threshold enforced; `opa check --strict` clean. |
| B-12 | tool-service mTLS listener with TLS registry, SPIFFE ID extraction, client-side server-ID pinning | Tests with generated certificates: no client cert → handshake fails; wrong trust domain → rejected; two URI SANs → rejected; rotated PEM files are picked up by server and client without restart. |
| B-13 | Tool catalogue, synthetic seed data (Flyway), read tools | Each tool validates arguments against its schema; data clearly synthetic; seed data implicates the checkout release (version, time, timeout change) consistently across tools. |
| B-14 | Enforcement flow: idempotency, deadline, trusted input assembly, OPA query (fail closed), audit, outbox event | Tests: OPA down → `503`, nothing executed; replay returns stored response with `replayed: true`; same key different args → `409`; concurrent duplicates execute once; deny is audited and never reaches a handler. |
| B-15 | Remediation tools (simulated) and approval grants | Without a grant → `DENIED approval_required`; with matching grant → one `simulated_action` row; digest mismatch → `approval_mismatch`; expired → `approval_expired`; only triage identity can register a grant. A static check proves no Kubernetes/cloud client is on tool-service's classpath. |

### M3 — Agents and workflow

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-16 | triage-agent persistence: schema, entities, state machine with the transition table as data | Property-style test: every transition not in architecture §4.1 is rejected; terminal states are immutable. |
| B-17 | triage-agent API, idempotent run creation, admission limits | Contract tests against OpenAPI; duplicate `Idempotency-Key` returns the same run; limits return `429`. |
| B-18 | Triage AI service → `TriagePlan`; dispatch through outbox; budgets | Deterministic-mode test produces the documented plan; plan over budget is truncated and recorded; invalid output gets one repair attempt then `NEEDS_REVIEW`. |
| B-19 | evidence-worker: consumer, AI service with tool methods calling tool-service, validation, result publication, heartbeat, model permit semaphore | Integration test with real NATS + tool-service + OPA: task completes; facts reference real `invocationId`s; deadline and tool-call caps enforced; `DROP_ACK_ONCE` produces two deliveries and one execution. |
| B-20 | Result folding in triage: inbox, optimistic concurrency, quorum, late events, dead-letter and advisory handling, deadline sweeper | Tests: N parallel results dispatch exactly one recommendation command; duplicate result ignored; lost task → `TIMED_OUT` by sweeper; exhausted deliveries → `NEEDS_REVIEW` with DLQ record and the message removed from the work stream. |
| B-21 | recommendation-agent: recommendation with citation validation; `ATTEMPT_UNAPPROVED`; approved action execution | Tests: citations to unknown evidence fail validation; unapproved attempt yields a `DENIED` attempt in the result and run lands in `AWAITING_APPROVAL`; approval path ends `COMPLETED (REMEDIATION_SIMULATED)` with exactly one simulated action even when the action command is redelivered. |
| B-22 | Burst endpoint and preset plans | Capped, single active burst; runs tagged `planSource: PRESET`, end `EVIDENCE_ONLY`. |
| B-23 | Metrics and span attributes from architecture §8 | A test scrapes each service and finds the documented metric names; an end-to-end test finds one trace containing spans from all five services. |

### M4 — Console backend and SPA

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-24 | ops-console API: proxying, presenter token, scenarios, config, audit read over mTLS | Mutations without token → `401`; contract tests pass; security headers present. |
| B-25 | Ops snapshot from NATS consumer info, oldest-message age, Kubernetes API, component health | With a source stopped, the corresponding fields are `null` and `status: UNAVAILABLE` (tested per source); values match the NATS CLI and `kubectl` in the kind e2e test. |
| B-26 | SSE stream with replay buffer, `Last-Event-ID`, `reset` | Reconnect test loses no events inside the buffer and emits `reset` beyond it. |
| B-27 | SPA per `docs/ui-design.md`: shell, tokens, themes, scenarios, topology, timeline, inspector, ops strip, presenter mode | Component tests; Playwright runs the three scenarios against the deterministic stack; axe reports no serious or critical violations in both themes; contrast test passes for every token pair; no text referencing any event; UI shows "Unavailable" (never zero) when the snapshot says so; generated API types only. |

### M5 — Deployment

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-28 | Dockerfiles, Kustomize base, kind overlay, NetworkPolicies, `nats-setup` Job | `kustomize build` valid for every overlay; all containers meet the restricted pod security profile (checked by a manifest policy test). |
| B-29 | Platform scripts: `preflight.sh`, `platform-install.sh` (KEDA, SPIRE via pinned Helm charts), `deploy.sh`, `smoke.sh`, `demo-reset.sh`, `uninstall.sh` | Scripts are idempotent (run twice, no diff/no error); mutating scripts print the target context and require confirmation; `shellcheck` clean. |
| B-30 | `e2e-kind` CI job | On kind at the pinned Kubernetes version: SPIRE issues SVIDs to all five workloads; smoke suite passes: success run with complete trace, policy denial with no side effect, burst scales evidence-worker above 1 and back, redelivery with one execution. This job joins `compat-gate`. |
| B-31 | Grafana dashboards, Prometheus config, Collector config, Jaeger config | Dashboards load without datasource errors in the e2e job; panels query metric names that exist (checked by a script against the live Prometheus in e2e). |
| B-32 | OpenShift overlay(s): Routes, ImageStreams, BuildConfigs, CMA variant, OpenShift values for KEDA and SPIRE | `kustomize build` valid; documented dry-run (`oc apply --dry-run=server`) procedure. **Not applied to any cluster without the user's instruction.** |
| B-33 | Cluster verification on the user's OpenShift cluster, **with the user** | Every item C1–C10 in deployment §8 and S2–S5 in security §9 is resolved and the outcome recorded in the docs; runbook §7 table filled with measured timings. |

### M6 — Maintenance automation

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-34 | `.github/dependabot.yml` per dependency-maintenance §3 | GitHub accepts the file (no configuration error on the Dependabot tab); groups behave as designed on the first run. |
| B-35 | `automerge.yml` (classify + merge) | Dry-run tests of the classifier for: patch, minor, major, Quarkus same-stream, Quarkus stream change. A PR is never merged on a SHA whose gate did not pass. |
| B-36 | `upgrade-compat.yml`, `upgrade-ownership.yml`, `ownership-check` | Report-only mode works with no extra token; with the token, a simulated older pin produces exactly one PR on the fixed branch and a second run updates it in place. |
| B-37 | Nightly live-model check and the standing issue | Issue is created once and edited thereafter. |

### M7 — Finish

| ID | Item | Acceptance criteria |
| --- | --- | --- |
| B-38 | README quick start verified from a clean clone on kind | A new user reaches a passing `make smoke` using only the README. |
| B-39 | Runbook rehearsal ×2 and recorded fallback | Runbook §7 complete; recording stored outside the repository. |

## 5. Definition of done for the project

1. All three required scenarios and the optional redelivery scenario pass in `e2e-kind`
   (deterministic) and in a rehearsal on the target cluster (live model).
2. `compat-gate` is green on `main`.
3. No open "verify on cluster" items.
4. The console shows no fabricated value in any failure-injection test.
5. `git log` contains no AI author, committer, or co-author.

## 6. Decisions changed during implementation

None yet. Record: date, ADR number, what changed, why.

## 7. Open prerequisites for the repository owner

| # | Needed from the owner | Blocks |
| --- | --- | --- |
| P1 | Model endpoint base URL, model name, and API key (provided locally in `.env.local`, never committed) | Live-mode rehearsal, B-33, B-37 |
| P2 | Token for the upgrade workflow (`UPGRADE_BOT_TOKEN`: GitHub App or fine-grained PAT) | B-36 beyond report-only |
| P3 | Decision on repository visibility/plan (rulesets and native auto-merge need a public repository or a paid plan) | Optional hardening in B-35 |
| P4 | Access window on the OpenShift cluster and acceptance of privileged SCCs for SPIRE | B-33 |
| P5 | A license for the repository, if it is to become public | — |
