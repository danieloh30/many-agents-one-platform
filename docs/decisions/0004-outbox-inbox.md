# 0004 — Transactional outbox and inbox

**Status:** Accepted

## Context
State changes and message publication must not diverge, and JetStream delivery is at-least-once.
JetStream's `Nats-Msg-Id` deduplication only covers a time window.

## Decision
- Services with a database write events to an `outbox_event` table in the same transaction as the
  state change. An in-process relay publishes rows using `FOR UPDATE SKIP LOCKED` and marks them
  published after the JetStream acknowledgement.
- Consumers with a database record `(consumer, event_id)` in an `inbox_event` table inside the
  handling transaction and acknowledge only after commit.
- Run rows carry an optimistic `version`; conflicting handlers retry.
- Change-data-capture (Debezium) is not used.

## Consequences
- Effectively-once state transitions on top of at-least-once delivery.
- Up to ~250 ms relay latency; acceptable.
- No Kafka Connect/Debezium deployment to operate or explain.
- Outbox backlog becomes a first-class metric and alert.
