# 0001 — Service boundaries and state ownership

**Status:** Accepted

## Context
The demo must show independently deployable triage, evidence, and recommendation agents plus a
separate protected tool service. Evidence workers scale horizontally and may be killed at any time.

## Decision
- Five services: `ops-console`, `triage-agent`, `evidence-worker`, `recommendation-agent`, `tool-service`.
- `triage-agent` is the only writer of run state and owns the `triage` schema.
- `tool-service` owns the `tools` schema (audit, idempotency, approval grants, simulated actions, synthetic data).
- `evidence-worker` and `recommendation-agent` are stateless. Their effects commit in triage (results,
  deduplicated by event ID) or in tool-service (executions, deduplicated by idempotency key).
- Workers cannot delegate (delegation depth 1).
- One shared library, `maop-common`, holds contract types and messaging/tracing plumbing only.

## Consequences
- Workers scale and fail freely; correctness does not depend on them.
- A redelivered task repeats its model calls (bounded by `MaxDeliver` and budgets) — accepted cost.
- Evidence travels inside events; payloads must stay small (enforced by schema limits).
- The shared library couples build versions of the services; acceptable in a single repository.
