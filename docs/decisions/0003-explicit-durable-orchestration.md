# 0003 — Explicit durable orchestration

**Status:** Accepted

## Context
The session's claim is that multi-agent applications must be operable, not just a chain of model
calls. In-process agent frameworks keep the workflow in memory: a restart loses it, and retries,
deadlines, and budgets are implicit.

## Decision
The workflow is an explicit state machine persisted in PostgreSQL and advanced by durable
messages. Each agent makes bounded, single-purpose model calls that return validated structured
output. Coordination, retries, deadlines, and budgets are application code and data, not model
behaviour.

## Consequences
- Any service can restart mid-run without losing the run.
- Behaviour is testable without a model (deterministic substitute) and observable step by step.
- More code than an agent-graph library; this is the point of the demonstration.
- No workflow engine (Temporal, etc.) is introduced: the state machine is small, and the building
  blocks being demonstrated are the CNCF ones.
