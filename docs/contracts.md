# Contracts

Versioned REST and event contracts. These are normative: implementation must match them, and a
contract change requires a doc change in the same commit. Schemas live in `contracts/` once
implemented (`contracts/openapi/*.yaml`, `contracts/events/*.schema.json`) and are validated in CI.

Conventions used everywhere:

- JSON, `camelCase` fields, RFC 3339 UTC timestamps, UUIDv7 identifiers rendered as lowercase strings.
- Unknown fields are ignored by readers (tolerant reader); writers never repurpose a field.
- Enum values are `UPPER_SNAKE`. Readers must treat an unknown enum value as a validation failure
  on commands and as `UNKNOWN` on observation events.

## 1. Identifiers

| Identifier | Format | Minted by | Meaning |
| --- | --- | --- | --- |
| `runId` | UUIDv7 | triage-agent | One incident investigation. Never reused. |
| `taskId` | UUIDv7 | triage-agent | One delegated unit of work (evidence task, recommendation, action execution). |
| event `id` | UUIDv7, or UUIDv5 for deterministic results (§3.3) | producer | CloudEvents `id`; unique per `source`. Also the JetStream `Nats-Msg-Id`. |
| `idempotencyKey` | `<taskId>:<toolName>:<sha256(canonical args)[0..16]>` | tool caller | Identifies one logical tool invocation across redeliveries. |
| `invocationId` | UUIDv7 | tool-service | One committed tool execution or denial (audit record key). |
| `decisionId` | string | OPA | OPA `decision_id` for the authorization query. |
| `approvalId` | UUIDv7 | triage-agent | One human approval of one proposed action. |
| `actionDigest` | `sha256:` + hex of canonical JSON of `{runId, actionType, target, parameters}` | recommendation-agent, recomputed by tool-service | Binds an approval to exactly one action. |
| `traceId` | 32 hex chars | OpenTelemetry | W3C trace ID of the run's root trace. |

Canonical JSON = RFC 8785 (JCS).

## 2. REST contracts

All paths are versioned in the URL (`/v1`). A breaking change creates `/v2` and both are served for
one release. Errors use RFC 9457 `application/problem+json` with an extra `code` member
(machine-readable, `UPPER_SNAKE`) and `traceId`.

### 2.1 Console API — `ops-console` (browser-facing, same origin as the SPA)

Base path `/api/v1`. Read endpoints are open inside the demo; **mutating endpoints require
`Authorization: Bearer <presenter token>`** (see [security.md](security.md) §6).

| Method | Path | Purpose | Notes |
| --- | --- | --- | --- |
| `GET` | `/api/v1/config` | UI bootstrap | Model mode, model name, Jaeger/Grafana base URLs, feature flags, limits, build versions. |
| `GET` | `/api/v1/scenarios` | Scenario presets | Static catalogue (§2.4). |
| `POST` | `/api/v1/runs` | Submit an incident | `Idempotency-Key` header required. Returns `202` + `Run`. Proxied to triage-agent. |
| `GET` | `/api/v1/runs?limit=&cursor=&label=` | List runs | Newest first, cursor pagination. |
| `GET` | `/api/v1/runs/{runId}` | Run detail | Durable business record: state, tasks, evidence, recommendation, approvals, usage. |
| `GET` | `/api/v1/runs/{runId}/timeline` | Durable timeline | Ordered `RunTimelineEntry[]` from the triage database. |
| `POST` | `/api/v1/runs/{runId}/approval` | Approve or reject the proposed action | Body `{decision: APPROVE\|REJECT, actionDigest, comment?}`. |
| `POST` | `/api/v1/runs/{runId}/cancel` | Cancel a non-terminal run | Idempotent. |
| `POST` | `/api/v1/bursts` | Start the bounded queue burst | Body `{runs?: int}`; server clamps to configured cap; `409 BURST_ACTIVE` if one is running. |
| `GET` | `/api/v1/audit/invocations?runId=` | Tool audit records | Read-through to tool-service over mTLS. |
| `GET` | `/api/v1/audit/invocations/{invocationId}` | One audit record with policy decision | |
| `GET` | `/api/v1/ops/snapshot` | Runtime snapshot | Queue, worker, replica, platform health (§2.5). Never persisted as business state. |
| `GET` | `/api/v1/stream` | Server-Sent Events | Live events and periodic ops snapshots (§2.6). |

### 2.2 Triage API — `triage-agent` (cluster-internal)

Base path `/v1`. Called only by `ops-console`.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/v1/runs` | Create run (`Idempotency-Key` required; same key + same body returns the original run, same key + different body → `409 IDEMPOTENCY_KEY_REUSED`). |
| `GET` | `/v1/runs`, `/v1/runs/{runId}`, `/v1/runs/{runId}/timeline` | Queries. `ETag` = run `version`. |
| `POST` | `/v1/runs/{runId}/approval` | Record approval/rejection. Requires `If-Match: <version>`; `412` on stale version. |
| `POST` | `/v1/runs/{runId}/cancel` | Cancel. |
| `POST` | `/v1/bursts` | Create preset-planned burst runs. |

`CreateRunRequest`:

```json
{
  "title": "Checkout error rate elevated after release",
  "description": "5xx on POST /checkout rose from 0.2% to 7% starting 14:05 UTC.",
  "service": "checkout",
  "scenario": "SUCCESS",
  "remediationMode": "PROPOSE_ONLY",
  "faultInjection": "NONE",
  "labels": { "source": "console" }
}
```

- `scenario`: `SUCCESS | POLICY_DENIAL | BURST | REDELIVERY | CUSTOM` — a label for the console; behaviour is
  driven only by the explicit fields below it.
- `remediationMode`: `PROPOSE_ONLY` (default) or `ATTEMPT_UNAPPROVED` (the agent tries to execute the
  simulated remediation without approval; policy is expected to deny).
- `faultInjection`: `NONE` (default) or `DROP_ACK_ONCE` (evidence agent deliberately abandons the first
  delivery of one task after its tool call commits). Accepted only when
  `maop.fault-injection.enabled=true`; always echoed on the run and shown in the UI.

`Run` (response of create/get):

```json
{
  "runId": "0197a6f2-6c1e-7b3a-9d2e-3f1c5a7b9e10",
  "version": 7,
  "state": "AWAITING_APPROVAL",
  "outcome": null,
  "scenario": "SUCCESS",
  "remediationMode": "PROPOSE_ONLY",
  "faultInjection": "NONE",
  "planSource": "MODEL",
  "incident": { "title": "...", "description": "...", "service": "checkout" },
  "classification": { "category": "RELEASE_REGRESSION", "severity": "SEV2", "confidence": 0.86,
                      "rationale": "Error onset follows checkout v2.14.0 rollout by 4 minutes." },
  "createdAt": "2026-01-01T14:10:00Z",
  "deadline": "2026-01-01T14:12:00Z",
  "approvalDeadline": "2026-01-01T14:22:00Z",
  "budget": { "maxEvidenceTasks": 4, "maxModelCalls": 12, "maxToolCallsPerTask": 4, "maxTotalTokens": 24000 },
  "usage":  { "modelCalls": 5, "toolCalls": 6, "inputTokens": 4120, "outputTokens": 890, "policyDenials": 0 },
  "model":  { "mode": "LIVE", "name": "<configured model>" },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "tasks": [ { "taskId": "...", "kind": "EVIDENCE", "topic": "DEPLOYMENT_HISTORY", "state": "COMPLETED",
               "deliveries": 1, "startedAt": "...", "finishedAt": "..." } ],
  "evidence": [ "EvidenceItem (§3.4)" ],
  "recommendation": "Recommendation (§3.4) or null",
  "approval": null
}
```

`state` and `outcome` values and transitions are defined in [architecture.md](architecture.md) §4.

### 2.3 Tool API — `tool-service` (mTLS only, port 8443)

There is no plaintext listener for this API. The caller is identified by the SPIFFE ID in the
X.509-SVID presented on the connection; no header can assert identity.

| Method | Path | Purpose | Allowed callers (enforced by OPA) |
| --- | --- | --- | --- |
| `POST` | `/v1/tools/{toolName}/invocations` | Invoke a tool | per-tool, see [security.md](security.md) §4 |
| `PUT` | `/v1/approvals/{approvalId}` | Register an approval grant | triage-agent only |
| `GET` | `/v1/invocations?runId=` / `/v1/invocations/{invocationId}` | Audit read | ops-console only |
| `GET` | `/v1/tools` | Tool catalogue with JSON Schemas | any registered workload |

`ToolInvocationRequest` (header `Idempotency-Key: <idempotencyKey>` required):

```json
{
  "runId": "…", "taskId": "…",
  "deadline": "2026-01-01T14:12:00Z",
  "arguments": { "service": "checkout", "limit": 5 },
  "approvalId": null
}
```

`ToolInvocationResponse` (`200`):

```json
{
  "invocationId": "…",
  "toolName": "deployment.get_history",
  "toolClass": "READ",
  "status": "EXECUTED",
  "replayed": false,
  "simulated": true,
  "dataOrigin": "SYNTHETIC",
  "decision": { "allow": true, "decisionId": "…", "policyRevision": "…", "reasons": [] },
  "caller": { "spiffeId": "spiffe://<trust-domain>/ns/maop/sa/evidence-agent" },
  "result": { "…tool specific…" },
  "executedAt": "…"
}
```

Status codes: `200` executed or replayed (`replayed: true` when the idempotency key was already
committed — the stored result is returned and nothing executes again); `403` + problem
`code=POLICY_DENIED` with `decision` and `invocationId` (a denial is audited and is also idempotent);
`409 IDEMPOTENCY_KEY_REUSED` (same key, different argument digest); `422` argument schema violation;
`408`/`504` `DEADLINE_EXCEEDED` (deadline already past — nothing executes); `503 POLICY_UNAVAILABLE`
(OPA unreachable — **fail closed**, nothing executes).

Tool catalogue (all data is synthetic; all remediation is simulated):

| Tool | Class | Arguments | Effect |
| --- | --- | --- | --- |
| `catalog.get_service` | `READ` | `service` | Owner, tier, runtime, dependencies. |
| `catalog.get_dependencies` | `READ` | `service`, `depth≤2` | Upstream/downstream graph. |
| `deployment.get_history` | `READ` | `service`, `limit≤10` | Recent rollouts with versions and times. |
| `deployment.get_diff` | `READ` | `service`, `fromVersion`, `toVersion` | Config and image changes between versions. |
| `telemetry.get_error_summary` | `READ` | `service`, `windowMinutes≤60` | Synthetic error-rate summary by endpoint. |
| `remediation.rollback_deployment` | `REMEDIATION` | `service`, `toVersion` | Writes a `simulated_action` row. Touches no cluster API. |
| `remediation.set_feature_flag` | `REMEDIATION` | `service`, `flag`, `enabled` | Writes a `simulated_action` row. |

`ApprovalGrant` (`PUT /v1/approvals/{approvalId}`):

```json
{ "runId": "…", "actionDigest": "sha256:…", "approver": "demo-operator",
  "approvedAt": "…", "expiresAt": "…" }
```

### 2.4 Scenario presets (`GET /api/v1/scenarios`)

| `id` | Request produced | Expected visible result |
| --- | --- | --- |
| `SUCCESS` | `remediationMode=PROPOSE_ONLY` | Run reaches `AWAITING_APPROVAL` with an evidence-backed proposal and one connected trace. |
| `POLICY_DENIAL` | `remediationMode=ATTEMPT_UNAPPROVED` | Tool audit shows `DENIED` with the rule that fired; no `simulated_action` row; run falls back to `AWAITING_APPROVAL`. |
| `BURST` | `POST /api/v1/bursts` | Queue depth rises, KEDA raises evidence-agent replicas, queue drains, replicas return to minimum. |
| `REDELIVERY` (optional) | `faultInjection=DROP_ACK_ONCE` | One task shows `deliveries: 2`; its tool invocation shows one execution and `replayed: true` on the second call. |

### 2.5 Ops snapshot (runtime, not business state)

```json
{
  "observedAt": "…",
  "queues": [
    { "stream": "MAOP_WORK", "consumer": "evidence-agents", "status": "OK",
      "pending": 23, "inFlight": 4, "redelivered": 1, "oldestMessageAgeSeconds": 11.4 }
  ],
  "workloads": [
    { "name": "evidence-agent", "status": "OK", "desiredReplicas": 4, "readyReplicas": 3,
      "minReplicas": 1, "maxReplicas": 6, "scaler": { "kind": "KEDA", "active": true, "ready": true },
      "pods": [ { "name": "evidence-agent-7c9…", "phase": "Running", "ready": true, "startedAt": "…", "activeTasks": 2 } ] }
  ],
  "platform": [
    { "component": "opa", "status": "OK" }, { "component": "spire", "status": "OK" },
    { "component": "jaeger", "status": "OK" }, { "component": "prometheus", "status": "UNAVAILABLE", "detail": "…" }
  ]
}
```

Every element has `status: OK | DEGRADED | UNAVAILABLE`. When a source cannot be read, its numeric
fields are **`null`**, never `0`, and the UI renders "unavailable". `activeTasks` comes from worker
heartbeat events and is `null` if no heartbeat was seen within 10 s.

### 2.6 SSE stream (`GET /api/v1/stream`)

- `Content-Type: text/event-stream`; comment heartbeat every 15 s.
- `event: run-event` — `data` is the CloudEvent in structured JSON form (§3). `id` is the
  JetStream stream sequence of `MAOP_EVENTS`.
- `event: ops-snapshot` — `data` is §2.5, emitted every 2 s and on change; no `id`.
- `event: reset` — sent when the client's `Last-Event-ID` is older than the replay buffer; the client
  must refetch run state over REST and continue.
- Optional query `runId=` filters `run-event`.

The stream is a view for rendering. The SPA must be able to rebuild any screen from REST alone.

## 3. Event contracts

### 3.1 Envelope

Every message is a [CloudEvents 1.0](https://github.com/cloudevents/spec) event. On NATS the
**binary content mode** is used: context attributes are NATS headers prefixed `ce-`, the message
payload is the JSON `data`. The SSE stream and the DLQ store use structured mode
(`application/cloudevents+json`).

| Attribute | Value |
| --- | --- |
| `specversion` | `1.0` |
| `id` | Event identity (§3.3) |
| `source` | `/maop/<service-name>` e.g. `/maop/triage-agent` |
| `type` | `io.github.danieloh30.maop.<entity>.<event>.v<major>` |
| `subject` | `runs/<runId>` or `runs/<runId>/tasks/<taskId>` |
| `time` | Producer wall-clock time of the business fact |
| `datacontenttype` | `application/json` |
| `dataschema` | `https://github.com/danieloh30/many-agents-one-platform/contracts/events/<type>.schema.json` |
| `traceparent`, `tracestate` | CloudEvents Distributed Tracing extension (W3C Trace Context) |
| `maoprunid` | Run ID (extension) |
| `maoptaskid` | Task ID when applicable (extension) |
| `maopdeadline` | Absolute run deadline, RFC 3339 (extension) |
| `maopmodelmode` | `LIVE` or `DETERMINISTIC` (extension) — makes test substitutes visible on every event |

Additional NATS headers (transport, not CloudEvents): `Nats-Msg-Id` = `ce-id`;
`traceparent`/`tracestate` duplicated un-prefixed so standard propagators work.

Versioning: the major version is part of `type`. Additive changes keep the version. A breaking
change publishes a new `…v2` type; producers dual-publish for one release; consumers subscribe by
type and reject unknown majors to the review path.

### 3.2 Streams, subjects, and consumers

| Stream | Subjects | Retention | Purpose |
| --- | --- | --- | --- |
| `MAOP_WORK` | `maop.work.v1.triage`, `maop.work.v1.evidence`, `maop.work.v1.recommendation` | WorkQueue, file, max age 1 h | Commands. Exactly one consumer per subject. |
| `MAOP_EVENTS` | `maop.events.v1.>` | Limits, file, max age 24 h | Facts and progress. Fan-out. |
| `MAOP_DLQ` | `maop.dlq.v1.>` | Limits, file, max age 7 d | Dead-lettered commands with failure metadata. |

All streams: duplicate window 2 min, 1 replica (demo; production uses 3).

| Durable pull consumer | Stream / filter | Owner | AckWait | MaxDeliver | MaxAckPending |
| --- | --- | --- | --- | --- | --- |
| `triage-workers` | `MAOP_WORK` / `maop.work.v1.triage` | triage-agent | 30 s | 3 | 16 |
| `evidence-agents` | `MAOP_WORK` / `maop.work.v1.evidence` | evidence-agent | 20 s | 3 | 64 |
| `recommendation-workers` | `MAOP_WORK` / `maop.work.v1.recommendation` | recommendation-agent | 30 s | 3 | 8 |
| `triage-results` | `MAOP_EVENTS` / `maop.events.v1.task.>` | triage-agent | 30 s | 5 | 64 |

Consumers deliberately set **no `BackOff` list**: in JetStream a `BackOff` list replaces `AckWait`
for acknowledgement timeouts and does not apply to negative acknowledgements. Instead, handlers
send an in-progress acknowledgement every `AckWait / 3` while working, and retryable failures are
negatively acknowledged with an explicit delay (2 s, then 5 s). Streams, consumers, and these
settings are created idempotently by the owning service at startup and are also declared in
`deploy/base/nats/` for review.

`ops-console` reads `MAOP_EVENTS` with an ephemeral ordered consumer per pod. Event subject =
`maop.events.v1.<entity>.<event>` mirroring the type suffix.

### 3.3 Event identity and deduplication

- Commands and facts written through an outbox get a UUIDv7 `id` when the outbox row is inserted;
  relay retries republish the same `id`.
- Terminal task results published by stateless workers use a **deterministic** `id` =
  UUIDv5(namespace = `taskId`, name = `<type>`), so a redelivered task yields the same event ID.
- Deduplication layers, weakest to strongest: JetStream duplicate window on `Nats-Msg-Id`
  (best effort, 2 min) → consumer **inbox table** keyed `(consumer, event id)` written in the same
  transaction as the state change (durable) → tool-service idempotency key (durable).

### 3.4 Event catalogue

Commands (`MAOP_WORK`):

| Type suffix | Producer → consumer | `data` |
| --- | --- | --- |
| `run.triage.requested.v1` | triage-agent → triage-agent | `{runId}` |
| `task.evidence.requested.v1` | triage-agent → evidence-agent | `EvidenceTaskCommand` |
| `task.recommendation.requested.v1` | triage-agent → recommendation-agent | `RecommendationCommand` |
| `task.action.requested.v1` | triage-agent → recommendation-agent | `ActionCommand` (approved execution) |

Facts consumed by triage (`MAOP_EVENTS`, `maop.events.v1.task.>`):

| Type suffix | Producer | `data` |
| --- | --- | --- |
| `task.evidence.completed.v1` | evidence-agent | `{taskId, evidence: EvidenceItem, usage}` |
| `task.recommendation.completed.v1` | recommendation-agent | `{taskId, recommendation: Recommendation, executionAttempt?, usage}` |
| `task.action.completed.v1` | recommendation-agent | `{taskId, invocationId, status: EXECUTED, replayed, usage}` |
| `task.failed.v1` | any worker | `{taskId, kind, reason, retryable: false, detail, deliveries, usage}` |
| `task.dead_lettered.v1` | any worker | `{taskId, kind, reason, deliveries, dlqSubject}` |

Observation events (`MAOP_EVENTS`; drive the live console, never drive state):

| Type suffix | Producer | `data` (abridged) |
| --- | --- | --- |
| `run.state_changed.v1` | triage-agent (outbox) | `{runId, from, to, outcome?, version, reason}` |
| `task.started.v1` | workers | `{taskId, kind, pod, delivery}` |
| `model.call.completed.v1` | agents | `{taskId?, purpose, model, mode, durationMs, inputTokens, outputTokens, status}` — no prompt or completion text |
| `tool.invocation.completed.v1` | tool-service (outbox) | `{invocationId, toolName, toolClass, status: EXECUTED\|DENIED\|REPLAYED, callerSpiffeId, decisionId, simulated}` |
| `worker.heartbeat.v1` | workers, every 5 s | `{service, pod, activeTasks, modelPermitsInUse}` |

Shared payload types:

```json
// EvidenceTaskCommand
{ "runId": "…", "taskId": "…", "topic": "DEPLOYMENT_HISTORY",
  "incident": { "title": "…", "description": "…", "service": "checkout" },
  "question": "What changed in checkout in the 60 minutes before the error increase?",
  "budget": { "maxModelCalls": 2, "maxToolCalls": 4, "maxOutputTokens": 800 },
  "deadline": "…", "faultInjection": "NONE" }

// EvidenceItem
{ "evidenceId": "…", "taskId": "…", "topic": "DEPLOYMENT_HISTORY",
  "finding": "checkout v2.14.0 rolled out at 14:01 UTC and lowered the payment client timeout from 2 s to 300 ms.",
  "confidence": 0.9,
  "facts": [ { "statement": "…", "invocationId": "…", "toolName": "deployment.get_diff" } ],
  "dataOrigin": "SYNTHETIC", "model": { "mode": "LIVE", "name": "…" } }

// RecommendationCommand
{ "runId": "…", "taskId": "…", "incident": {…}, "classification": {…},
  "evidence": [ EvidenceItem ], "evidenceCompleteness": "COMPLETE",
  "remediationMode": "PROPOSE_ONLY", "budget": {…}, "deadline": "…" }

// Recommendation
{ "summary": "Roll back checkout to v2.13.2.",
  "explanation": "Concise, user-facing justification (≤ 600 chars).",
  "proposedAction": { "actionType": "ROLLBACK_DEPLOYMENT", "target": "checkout",
                      "parameters": { "toVersion": "2.13.2" }, "sideEffect": true,
                      "actionDigest": "sha256:…" },
  "citations": [ "<evidenceId>", "…" ],
  "confidence": 0.82, "risks": [ "…" ] }

// executionAttempt (only when remediationMode = ATTEMPT_UNAPPROVED)
{ "invocationId": "…", "status": "DENIED", "decisionId": "…", "reasons": [ "approval_required" ] }
```

`topic` ∈ `SERVICE_CATALOG | DEPLOYMENT_HISTORY | CONFIG_DIFF | ERROR_SUMMARY`.
`actionType` ∈ `ROLLBACK_DEPLOYMENT | SET_FEATURE_FLAG | ESCALATE_TO_HUMAN | OBSERVE`
(the last two have `sideEffect: false` and never call a remediation tool).
`task.failed.reason` ∈ `DEADLINE_EXCEEDED | BUDGET_EXHAUSTED | MODEL_OUTPUT_INVALID | MODEL_UNAVAILABLE |
TOOL_DENIED | TOOL_UNAVAILABLE | INVALID_COMMAND | INTERNAL`.

Model-facing structured outputs (`TriagePlan`, `EvidenceItem`, `Recommendation`) are validated
against their schema and domain rules (every citation must reference an existing `evidenceId`;
every fact must reference an `invocationId` returned to that task). Explanations are concise,
user-facing text. Reasoning traces are never requested, stored, or displayed.

## 4. Compatibility rules checked in CI

1. OpenAPI and JSON Schema files are linted and diffed against the base branch; removing or
   retyping a field in a `v1` contract fails the build.
2. Every event type in §3.4 has a schema and a producer serialization test plus a consumer
   deserialization test that read the same golden sample file.
3. The SPA's TypeScript types (`web/src/api/types.ts`) are hand-written from this document and SSE payloads are validated with zod (ADR-0016 item 7).

## 5. Implementation-level details (normative for code and SPA)

Added at the start of implementation; these fill gaps in §2–§3 and take precedence where more specific.

### 5.1 Additional console endpoints

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/session` | Bearer | `204` if the presenter token is valid, `401` otherwise. Lets the UI validate a token. |
| `GET` | `/api/v1/bursts/current` | open | `{active, burstId, runs, completed, startedAt}`; `active=false` and nulls when none. |
| `POST` | `/api/v1/bursts/stop` | Bearer | Cancels non-terminal runs of the active burst and purges queued evidence work. Idempotent. |
| `POST` | `/api/v1/admin/reset` | Bearer | Deletes demonstration state only (runs, audit, simulated actions, queued messages). Never touches platform components. `409 RESET_REFUSED` while a burst is active. |

`POST /api/v1/runs` request = triage `CreateRunRequest` (§2.2). The console derives it from a preset
or the editable form.

### 5.2 Response shapes used by the SPA

- `GET /api/v1/config` →
  ```json
  { "product": "Agent Operations Console", "version": "0.1.0", "profile": "summit",
    "model": { "mode": "LIVE", "name": "gpt-4o-mini" },
    "identity": { "mode": "SPIFFE", "trustDomain": "maop.example" },
    "links": { "jaeger": "https://…", "grafana": "https://…", "grafanaDashboard": "https://…/d/maop-overview",
               "prometheus": "https://…" },
    "limits": { "burstMaxRuns": 12, "maxEvidenceTasks": 4 },
    "features": { "faultInjection": true },
    "auth": { "presenterTokenRequired": false },
    "kubernetes": { "enabled": true } }
  ```
  Any `links.*` value is `null` when that UI is not reachable/exposed. `identity.mode` is
  `SPIFFE` or `DEV_STATIC`. `kubernetes.enabled` is `false` on the cluster-free local stack, where the ops
  snapshot's workload fields stay `null` + `UNAVAILABLE`; the UI then labels them "Not on local stack" in a neutral
  tone instead of as a failure. When it is `true`, an `UNAVAILABLE` workload is a real problem.
- `GET /api/v1/runs` → `{ "items": [RunSummary], "nextCursor": null }` where
  `RunSummary = {runId, state, outcome, scenario, title, service, planSource, burstId, createdAt, updatedAt, traceId, labels}`;
  `labels` is the object given at creation (`{}` when none). `./demo.sh verify` labels its runs `{"source": "demo-verify"}`,
  which the console shows as "smoke test".
- `GET /api/v1/runs/{runId}/timeline` → `{ "items": [ {seq, at, kind, from, to, taskId, message, data} ] }`;
  `kind ∈ STATE_CHANGED | TASK_DISPATCHED | TASK_STARTED | TASK_COMPLETED | TASK_FAILED | TASK_DEAD_LETTERED | POLICY_DENIAL | APPROVAL | IGNORED_LATE | BUDGET | NOTE`.
- `GET /api/v1/audit/invocations?runId=` → `{ "items": [ToolInvocation] }` with
  ```json
  { "invocationId": "…", "runId": "…", "taskId": "…", "toolName": "…", "toolClass": "READ",
    "status": "EXECUTED", "requests": 2, "executions": 1, "idempotencyKey": "…",
    "simulated": true, "dataOrigin": "SYNTHETIC",
    "caller": { "spiffeId": "spiffe://…", "serial": "…", "notAfter": "…" },
    "decision": { "allow": true, "decisionId": "…", "policyRevision": "…", "reasons": [] },
    "arguments": {…}, "result": {…}, "sideEffect": null,
    "createdAt": "…", "lastRequestedAt": "…" }
  ```
  `sideEffect` (remediation only, when executed) = `{actionId, actionType, target, parameters, simulated: true}`.
- `GET /api/v1/runs/{runId}` → `Run` as in §2.2, plus `burstId`, `updatedAt`, `evidenceCompleteness`,
  `executionAttempt` (from the recommendation result) and `approval = {approvalId, decision, actionDigest, approver, decidedAt, expiresAt}`.

### 5.3 Additional observation events

| Type suffix | Producer | `data` |
| --- | --- | --- |
| `task.dispatched.v1` | triage-agent (outbox) | `{taskId, kind, topic}` — command handed to the work queue |
| `burst.changed.v1` | triage-agent (outbox) | `{burstId, active, runs, completed}` |
| `demo.reset.v1` | triage-agent | `{}` — clients drop cached state and refetch |

`model.call.completed.v1` and `task.started.v1` data also carry `service` (the emitting service
name). `tool.invocation.completed.v1` data also carries `runId`, `taskId`, `reasons`.
