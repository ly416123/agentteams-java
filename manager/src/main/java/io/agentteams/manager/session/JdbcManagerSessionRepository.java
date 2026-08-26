package io.agentteams.manager.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public final class JdbcManagerSessionRepository implements ManagerSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcManagerSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ManagerSessionRecord insertSession(ManagerSessionRecord session, String idempotencyKey) {
        int inserted = jdbc.update("""
                INSERT INTO manager_sessions
                    (id, tenant_id, project_id, actor, status, version, idempotency_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, actor, idempotency_key) DO NOTHING
                """, session.id(), session.tenantId(), session.projectId(), session.actor(),
                session.status().name(), session.version(), idempotencyKey, session.createdAt(), session.updatedAt());
        if (inserted == 0) {
            return jdbc.query("""
                    SELECT id, tenant_id, project_id, actor, status, version, created_at, updated_at
                      FROM manager_sessions
                     WHERE tenant_id = ? AND project_id = ? AND actor = ? AND idempotency_key = ?
                    """,
                    this::mapSession, session.tenantId(), session.projectId(), session.actor(), idempotencyKey)
                    .stream().findFirst().orElseThrow(() -> new DuplicateKeyException("session idempotency conflict"));
        }
        return session;
    }

    @Override
    public Optional<ManagerSessionRecord> findSession(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, project_id, actor, status, version, created_at, updated_at
                  FROM manager_sessions WHERE id = ?
                """, this::mapSession, id).stream().findFirst();
    }

    @Override
    public Optional<ManagerMessageRecord> findMessage(UUID sessionId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, session_id, idempotency_key, actor, role, content_hash,
                       redacted_summary, result_summary, status, created_at
                  FROM manager_messages WHERE session_id = ? AND idempotency_key = ?
                """, this::mapMessage, sessionId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public MessageReservation reserveMessage(UUID sessionId, long expectedVersion,
            ManagerMessageRecord message) {
        int inserted = jdbc.update("""
                INSERT INTO manager_messages
                    (id, session_id, idempotency_key, actor, role, content_hash,
                     redacted_summary, result_summary, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, idempotency_key) DO NOTHING
                """, message.id(), message.sessionId(), message.idempotencyKey(), message.actor(), message.role(),
                message.contentHash(), message.redactedSummary(), message.resultSummary(), message.status().name(),
                message.createdAt());
        if (inserted == 0) {
            ManagerMessageRecord existing = findMessage(sessionId, message.idempotencyKey())
                    .orElseThrow(() -> new DuplicateKeyException("message idempotency reservation disappeared"));
            return new MessageReservation(findSession(sessionId).orElseThrow(() -> new ManagerSessionNotFoundException(sessionId)),
                    existing, false);
        }
        ManagerSessionRecord current = findSession(sessionId)
                .orElseThrow(() -> new ManagerSessionNotFoundException(sessionId));
        if (current.status() == ManagerSessionRecord.Status.CANCELLED) {
            throw new SessionCancelledException();
        }
        ManagerSessionRecord updated = updateSession(sessionId, expectedVersion,
                ManagerSessionRecord.Status.ACTIVE, message.createdAt());
        return new MessageReservation(updated, message, true);
    }

    @Override
    public ManagerMessageRecord completeMessage(UUID sessionId, String idempotencyKey, String resultSummary) {
        jdbc.update("""
                UPDATE manager_messages SET result_summary = ?, redacted_summary = 'message accepted', status = 'COMPLETED'
                 WHERE session_id = ? AND idempotency_key = ?
                """, resultSummary, sessionId, idempotencyKey);
        return findMessage(sessionId, idempotencyKey)
                .orElseThrow(() -> new ManagerSessionNotFoundException(sessionId));
    }

    @Override
    public ManagerMessageRecord failMessage(UUID sessionId, String idempotencyKey, String resultSummary) {
        jdbc.update("""
                UPDATE manager_messages SET result_summary = ?, status = 'FAILED'
                 WHERE session_id = ? AND idempotency_key = ?
                """, resultSummary, sessionId, idempotencyKey);
        return findMessage(sessionId, idempotencyKey)
                .orElseThrow(() -> new ManagerSessionNotFoundException(sessionId));
    }

    @Override
    public void insertMessage(ManagerMessageRecord message) {
        jdbc.update("""
                INSERT INTO manager_messages
                    (id, session_id, idempotency_key, actor, role, content_hash,
                     redacted_summary, result_summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, message.id(), message.sessionId(), message.idempotencyKey(), message.actor(), message.role(),
                message.contentHash(), message.redactedSummary(), message.resultSummary(), message.createdAt());
    }

    @Override
    public Optional<ManagerToolCallRecord> findToolCall(UUID sessionId, String idempotencyKey) {
        return jdbc.query("""
                SELECT id, session_id, idempotency_key, tool_name, input_hash,
                       status, result_summary, created_at
                  FROM manager_tool_calls WHERE session_id = ? AND idempotency_key = ?
                """, this::mapToolCall, sessionId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public void insertToolCall(ManagerToolCallRecord toolCall) {
        jdbc.update("""
                INSERT INTO manager_tool_calls
                    (id, session_id, idempotency_key, tool_name, input_hash,
                     status, result_summary, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, toolCall.id(), toolCall.sessionId(), toolCall.idempotencyKey(), toolCall.toolName(),
                toolCall.inputHash(), toolCall.status(), toolCall.resultSummary(), toolCall.createdAt());
    }

    @Override
    public ManagerSessionRecord updateSession(UUID id, long expectedVersion,
            ManagerSessionRecord.Status status, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE manager_sessions SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status.name(), updatedAt, id, expectedVersion);
        if (updated == 0) {
            long actual = findSession(id).map(ManagerSessionRecord::version).orElse(-1L);
            throw new SessionVersionConflictException(id, expectedVersion, actual);
        }
        return findSession(id).orElseThrow(() -> new ManagerSessionNotFoundException(id));
    }

    @Override
    public ManagerEventRecord appendEvent(UUID sessionId, String type, String payload, String idempotencyKey,
            Instant createdAt) {
        Long cursor = jdbc.query("SELECT nextval('manager_event_cursor_seq')",
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElseThrow();
        int inserted = jdbc.update("""
                INSERT INTO manager_events
                    (session_id, cursor, idempotency_key, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (session_id, idempotency_key) DO NOTHING
                """, sessionId, cursor, idempotencyKey, type, payload, createdAt);
        if (inserted == 0) {
            return findEvent(sessionId, idempotencyKey)
                    .orElseThrow(() -> new DuplicateKeyException("event idempotency conflict"));
        }
        return new ManagerEventRecord(sessionId, cursor, type, payload, createdAt);
    }

    @Override
    public Optional<ManagerEventRecord> findEvent(UUID sessionId, String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return jdbc.query("""
                SELECT session_id, cursor, event_type, payload::text, created_at
                  FROM manager_events WHERE session_id = ? AND idempotency_key = ?
                """, this::mapEvent, sessionId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public List<ManagerEventRecord> findEventsAfter(UUID sessionId, long cursor) {
        return jdbc.query("""
                SELECT session_id, cursor, event_type, payload::text, created_at
                  FROM manager_events WHERE session_id = ? AND cursor > ? ORDER BY cursor
                """, this::mapEvent, sessionId, cursor);
    }

    private ManagerSessionRecord mapSession(ResultSet rs, int row) throws SQLException {
        return new ManagerSessionRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("actor"),
                ManagerSessionRecord.Status.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getObject("created_at", Instant.class), rs.getObject("updated_at", Instant.class));
    }

    private ManagerMessageRecord mapMessage(ResultSet rs, int row) throws SQLException {
        return new ManagerMessageRecord(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("actor"), rs.getString("role"),
                rs.getString("content_hash"), rs.getString("redacted_summary"), rs.getString("result_summary"),
                ManagerMessageRecord.Status.valueOf(rs.getString("status")), rs.getObject("created_at", Instant.class));
    }

    private ManagerToolCallRecord mapToolCall(ResultSet rs, int row) throws SQLException {
        return new ManagerToolCallRecord(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getString("idempotency_key"), rs.getString("tool_name"), rs.getString("input_hash"),
                rs.getString("status"), rs.getString("result_summary"), rs.getObject("created_at", Instant.class));
    }

    private ManagerEventRecord mapEvent(ResultSet rs, int row) throws SQLException {
        return new ManagerEventRecord(rs.getObject("session_id", UUID.class), rs.getLong("cursor"),
                rs.getString("event_type"), rs.getString("payload"), rs.getObject("created_at", Instant.class));
    }
}
