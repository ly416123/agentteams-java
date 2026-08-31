package io.agentteams.controlplane.matrix;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class MatrixOutboundRepository {
    private final JdbcTemplate jdbc;

    public MatrixOutboundRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc"); }

    public UUID enqueue(String roomId, String eventType, String body, Instant now) {
        UUID id = UUID.randomUUID();
        enqueue(id, roomId, eventType, body, now);
        return id;
    }

    /** Enqueues a caller-owned id so Channel retries remain idempotent across process restarts. */
    public boolean enqueue(UUID id, String roomId, String eventType, String body, Instant now) {
        return jdbc.update("""
                INSERT INTO matrix_outbox_messages(id, room_id, event_type, body, status, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, roomId, eventType, body, java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)) == 1;
    }

    public List<MatrixOutboundMessage> claimDue(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM matrix_outbox_messages
                     WHERE status = 'PENDING' AND next_attempt_at <= ?
                     ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE matrix_outbox_messages message
                   SET status = 'IN_FLIGHT', attempts = message.attempts + 1, updated_at = ?
                  FROM due WHERE message.id = due.id
                RETURNING message.id, message.room_id, message.event_type, message.body,
                    message.status, message.attempts, message.next_attempt_at, message.last_error
                """, this::map, java.sql.Timestamp.from(now), limit, java.sql.Timestamp.from(now));
    }

    public void markSent(UUID id, Instant now) {
        jdbc.update("UPDATE matrix_outbox_messages SET status = 'SENT', updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now), id);
    }

    public void retry(UUID id, Instant nextAttemptAt, String error, Instant now) {
        jdbc.update("""
                UPDATE matrix_outbox_messages SET status = 'PENDING', next_attempt_at = ?,
                    last_error = ?, updated_at = ? WHERE id = ?
                """, java.sql.Timestamp.from(nextAttemptAt), error, java.sql.Timestamp.from(now), id);
    }

    private MatrixOutboundMessage map(ResultSet rs, int row) throws SQLException {
        return new MatrixOutboundMessage(rs.getObject("id", UUID.class), rs.getString("room_id"),
                rs.getString("event_type"), rs.getString("body"), rs.getString("status"),
                rs.getInt("attempts"), rs.getTimestamp("next_attempt_at").toInstant(), rs.getString("last_error"));
    }
}
