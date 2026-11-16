# 0015 — A console backend serves the SPA

**Status:** Accepted

## Context
The console needs runs (from triage), audit records (from tool-service), queue statistics (from
NATS), and replica status (from the Kubernetes API), plus a live stream. Putting Kubernetes API
credentials or audit access into a model-calling agent would widen its privileges.

## Decision
- `ops-console` is a separate Quarkus service: it serves the built React application as static
  resources, exposes the console API and SSE, and aggregates read-only runtime data.
- It is the only workload with a ServiceAccount token (read-only, namespace-scoped).
- It holds no business state; an in-memory replay buffer backs SSE resume.
- The SPA is built by Vite and copied into the service at package time; no separate web server
  image and no Quarkiverse web-bundling extension.

## Consequences
- One origin: no CORS, simple CSP, one Route.
- Agents hold no cluster credentials.
- Frontend and console backend ship together; acceptable for one team and one repository.
