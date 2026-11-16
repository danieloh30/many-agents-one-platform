# 0007 — OPA decides, tool-service enforces

**Status:** Accepted

## Context
Authorization must be enforced at runtime at a separate protected tool service, before any side
effect, and must not be bypassable by an agent whose behaviour a model influences.

## Decision
- OPA runs as a sidecar of tool-service, reachable on localhost only; policy is Rego v1 delivered
  as a content-hashed ConfigMap.
- tool-service is the enforcement point: it builds the policy input from trusted sources only
  (verified connection identity, its own tool catalogue, its own approval store), queries OPA over
  REST with a short timeout, and fails closed.
- Approval grants are stored in tool-service and can be written only by triage-agent's identity;
  they are bound to a run and an action digest.
- Decisions (allow and deny) are persisted with the OPA decision ID in the same transaction as the effect.
- No OPA SDK dependency; a REST client is sufficient.

## Consequences
- An agent cannot assert identity or approval.
- Policy changes roll the pod (no hot bundle server) — simple and auditable.
- OPA outage stops tool execution; intended.
