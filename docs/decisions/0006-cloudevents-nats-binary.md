# 0006 — CloudEvents binary mode on NATS

**Status:** Accepted

## Context
Events need a standard envelope. The CloudEvents NATS protocol binding defines binary mode with
`ce-`-prefixed headers (NATS ≥ 2.2). The CloudEvents Java SDK has no NATS binding module.

## Decision
- CloudEvents 1.0 everywhere. Binary mode on NATS (attributes as `ce-*` headers, JSON data as payload).
- Structured mode (`application/cloudevents+json`) for SSE and DLQ records.
- Use `cloudevents-core` for the model and `cloudevents-json-jackson` for structured mode; map
  NATS headers by hand in the adapter.
- Extensions: `traceparent`/`tracestate` (distributed tracing extension), `maoprunid`,
  `maoptaskid`, `maopdeadline`, `maopmodelmode`.
- Event `type` carries the major version.

## Consequences
- Routing and tracing work without parsing payloads.
- A small amount of mapping code with golden-file tests.
