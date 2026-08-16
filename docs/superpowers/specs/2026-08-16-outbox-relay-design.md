# Transactional Outbox Relay and NATS JetStream Publisher

## Goal

Deliver the control-plane outbox relay for durable, at-least-once publication to
NATS JetStream without making process memory authoritative for delivery state.

## Design

`OutboxEventRepository` claims due rows inside a short database transaction with
`FOR UPDATE SKIP LOCKED`. A claim changes the row to `IN_FLIGHT`, increments the
attempt counter, and stores a lease deadline in `next_attempt_at`. Expired
`IN_FLIGHT` rows are eligible for reclaim, so a relay crash does not strand an
event. A successful JetStream acknowledgement changes the row to `PUBLISHED`.
Failures return the row to `PENDING` with a bounded exponential delay, except
for attempt 10, which changes it to `DEAD_LETTER`.

`EventSubjects` maps agent and task aggregates to their keyed subjects and maps
all other aggregate types to `control.events`. Dead-letter publications use
`deadletter.events`.

`NatsEventPublisher` publishes a single `EventEnvelope` containing
`event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `aggregate_version`,
`occurred_at`, and nested `payload`. The outbox `event_id` is passed as the
JetStream message ID for broker-side deduplication. The relay marks the row
published only after `publish` returns its acknowledgement.

`OutboxRelay` owns scheduling and bounded worker concurrency, but not delivery
state. Every worker claims from PostgreSQL, publishes through the publisher,
and persists the resulting state. Dead-letter logs include event identifiers,
subject, attempt number, and a sanitized exception summary; task contents,
credential values, bearer tokens, passwords, API keys, and URLs with secrets are
not logged.

## Testing

Unit/contract tests cover subject mapping, envelope shape, message ID use,
acknowledgement ordering, retry delay bounds, tenth-attempt dead-lettering, and
log redaction. `OutboxRelayIT` uses PostgreSQL and a NATS JetStream container to
verify published state, duplicate message deduplication, retry recovery, and
dead-letter state. Tests are skipped when Docker is unavailable using the same
Testcontainers convention as the existing persistence tests.

