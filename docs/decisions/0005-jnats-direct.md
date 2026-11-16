# 0005 — Use jnats directly

**Status:** Accepted

## Context
A Quarkiverse reactive-messaging connector for JetStream exists. It is outside the platform BOM,
was recently reworked, releases very frequently, and does not document dead-letter handling or
message termination. The design needs explicit pull consumers, in-progress acknowledgements,
delayed negative acknowledgements, advisories, per-message headers, and consumer info for the
operations view.

## Decision
Use `io.nats:jnats` behind a small adapter in `maop-common` (`publish`, `subscribePull`,
`ack/nak/inProgress`, consumer info, stream/consumer provisioning). Handlers run on virtual threads.

## Consequences
- Full control and one stable dependency; the adapter is ours to maintain and test.
- Trace propagation and CloudEvents mapping are implemented in the adapter (tested with a real NATS container).
- No Dev Service for NATS; local development uses a compose file.
