package io.agentteams.controlplane.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class OutboxEventRepository {

    private final JdbcTemplate jdbc;

    OutboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OutboxEventRecord event) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, event_id, aggregate_type, aggregate_id, event_type, payload, aggregate_version,
                     occurred_at, status, attempts, next_attempt_at, last_error, claim_token,
                     created_at, updated_at, version, correlation_id, traceparent, tracestate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                JdbcSupport.json(event.payloadJson()), event.aggregateVersion(), JdbcSupport.timestamp(event.occurredAt()),
                event.status(), event.attempts(),
                JdbcSupport.timestamp(event.nextAttemptAt()), event.lastError(),
                event.claimToken(), JdbcSupport.timestamp(event.createdAt()), JdbcSupport.timestamp(event.updatedAt()),
                event.version(), event.correlationId(), event.traceparent(), event.tracestate());
    }

    public Optional<OutboxEventRecord> findByEventId(UUID eventId) {
        return jdbc.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload::text,
                       aggregate_version, occurred_at, status, attempts, next_attempt_at, last_error,
                       claim_token, created_at, updated_at, version, correlation_id, traceparent, tracestate
                  FROM outbox_events WHERE event_id = ?
                """, this::map, eventId).stream().findFirst();
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
        return count == null ? 0 : count;
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM outbox_events
                 WHERE status IN ('PENDING', 'IN_FLIGHT')
                """, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Instant> oldestPendingAt() {
        return jdbc.query("""
                SELECT min(created_at) AS oldest_created_at
                  FROM outbox_events
                 WHERE status IN ('PENDING', 'IN_FLIGHT')
                """, resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            java.sql.Timestamp timestamp = resultSet.getTimestamp("oldest_created_at");
            return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
        });
    }

    public java.util.List<String> eventTypes() {
        return jdbc.query("SELECT event_type FROM outbox_events ORDER BY event_type",
                (rs, row) -> rs.getString(1));
    }

    public java.util.List<OutboxEventRecord> findDue(Instant at, int limit) {
        return jdbc.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload::text,
                       aggregate_version, occurred_at, status, attempts, next_attempt_at, last_error,
                       claim_token, created_at, updated_at, version, correlation_id, traceparent, tracestate
                  FROM outbox_events
                 WHERE status = 'PENDING' AND next_attempt_at <= ?
                 ORDER BY next_attempt_at, created_at
                 LIMIT ?
                """, this::map, JdbcSupport.timestamp(at), limit);
    }

    public List<OutboxEventRecord> claimDue(Instant now, int limit, Duration lease) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }

        List<OutboxEventRecord> due = jdbc.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload::text,
                       aggregate_version, occurred_at, status, attempts, next_attempt_at, last_error,
                       claim_token, created_at, updated_at, version, correlation_id, traceparent, tracestate
                  FROM outbox_events
                 WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                    OR (status = 'IN_FLIGHT' AND next_attempt_at <= ?)
                 ORDER BY next_attempt_at, created_at
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, this::map, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now), limit);

        Instant leaseUntil = now.plus(lease);
        List<OutboxEventRecord> claimed = new ArrayList<>(due.size());
        for (OutboxEventRecord event : due) {
            UUID claimToken = UUID.randomUUID();
            jdbc.update("""
                    UPDATE outbox_events
                       SET status = 'IN_FLIGHT', attempts = attempts + 1, next_attempt_at = ?,
                           claim_token = ?, updated_at = ?, version = version + 1
                     WHERE id = ?
                    """, JdbcSupport.timestamp(leaseUntil), claimToken, JdbcSupport.timestamp(now), event.id());
            claimed.add(new OutboxEventRecord(event.id(), event.eventId(), event.aggregateType(), event.aggregateId(),
                    event.eventType(), event.payloadJson(), event.aggregateVersion(), event.occurredAt(),
                    "IN_FLIGHT", event.attempts() + 1, leaseUntil, event.lastError(), claimToken,
                    event.createdAt(), now, event.version() + 1, event.correlationId(), event.traceparent(),
                    event.tracestate()));
        }
        return claimed;
    }

    public void markPublished(OutboxEventRecord event, Instant at) {
        jdbc.update("""
                UPDATE outbox_events
                   SET status = 'PUBLISHED', claim_token = NULL, last_error = NULL,
                       next_attempt_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND status = 'IN_FLIGHT' AND claim_token = ?
                """, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), event.id(), event.claimToken());
    }

    public void markRetry(OutboxEventRecord event, Instant nextAttemptAt, String error, Instant at) {
        jdbc.update("""
                UPDATE outbox_events
                   SET status = 'PENDING', claim_token = NULL, last_error = ?,
                       next_attempt_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND status = 'IN_FLIGHT' AND claim_token = ?
                """, error, JdbcSupport.timestamp(nextAttemptAt), JdbcSupport.timestamp(at), event.id(),
                event.claimToken());
    }

    public void markDeadLetter(OutboxEventRecord event, String error, Instant at) {
        jdbc.update("""
                UPDATE outbox_events
                   SET status = 'DEAD_LETTER', claim_token = NULL, last_error = ?,
                       next_attempt_at = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND status = 'IN_FLIGHT' AND claim_token = ?
                """, error, JdbcSupport.timestamp(at), JdbcSupport.timestamp(at), event.id(), event.claimToken());
    }

    private OutboxEventRecord map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OutboxEventRecord(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"), rs.getString("payload"), rs.getLong("aggregate_version"),
                JdbcSupport.instant(rs, "occurred_at"), rs.getString("status"), rs.getInt("attempts"),
                JdbcSupport.instant(rs, "next_attempt_at"), rs.getString("last_error"),
                rs.getObject("claim_token", UUID.class),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("correlation_id"), rs.getString("traceparent"),
                rs.getString("tracestate"));
    }
}
