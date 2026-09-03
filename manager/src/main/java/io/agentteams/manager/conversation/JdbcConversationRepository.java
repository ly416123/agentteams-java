package io.agentteams.manager.conversation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the conversation durable boundary. */
public class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbc;

    public JdbcConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey) {
        return saveSession(context, status, createdAt, idempotencyKey, null);
    }

    @Override
    public ConversationRecord saveSession(ConversationRuntimePort.Context context, ConversationService.Status status,
            Instant createdAt, String idempotencyKey, ConversationOwner owner) {
        int inserted = jdbc.update("""
                INSERT INTO conversation_sessions
                    (id, project_id, team_id, worker_id, task_id, status, create_idempotency_key, created_at, updated_at,
                     tenant_id, actor_subject, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """, context.sessionId(), context.project(), context.team(), context.worker(), context.task(),
                status.name(), idempotencyKey, Timestamp.from(createdAt), Timestamp.from(createdAt),
                owner == null ? null : owner.tenantId(), owner == null ? null : owner.subject());
        ConversationRecord existing = (inserted == 0
                ? findSession(context.sessionId()).or(() -> findSessionByIdempotencyKey(idempotencyKey))
                : findSession(context.sessionId()))
                .orElseThrow(() -> new DuplicateKeyException("conversation session disappeared"));
        if (!existing.context().equals(context)) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                    "conversation session already exists with different context");
        }
        return existing;
    }

    @Override
    public Optional<ConversationRecord> findSession(UUID sessionId) {
        return jdbc.query("""
                SELECT id, project_id, team_id, worker_id, task_id, status, created_at, updated_at,
                       tenant_id, actor_subject, version
                  FROM conversation_sessions WHERE id = ?
                """, this::mapSession, sessionId).stream().findFirst();
    }

    @Override
    public Optional<ConversationRecord> findSessionByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return jdbc.query("""
                SELECT id, project_id, team_id, worker_id, task_id, status, created_at, updated_at,
                       tenant_id, actor_subject, version
                  FROM conversation_sessions WHERE create_idempotency_key = ?
                """, this::mapSession, idempotencyKey).stream().findFirst();
    }

    @Override
    public List<ConversationRecord> findSessions(String tenantId, String projectId, String actorSubject,
            Instant beforeUpdatedAt, UUID beforeId, int limit) {
        if (beforeUpdatedAt == null) {
            return jdbc.query("""
                    SELECT c.id, c.project_id, c.team_id, c.worker_id, c.task_id, c.status, c.created_at, c.updated_at,
                           c.tenant_id, c.actor_subject, c.version
                      FROM conversation_sessions c
                      JOIN projects p ON p.tenant_id = c.tenant_id
                                     AND (p.id::text = c.project_id OR p.name = c.project_id)
                     WHERE c.tenant_id = ? AND p.id::text = ? AND c.actor_subject = ?
                     ORDER BY c.updated_at DESC, c.id DESC
                     LIMIT ?
                    """, this::mapSession, tenantId, projectId, actorSubject, limit);
        }
        return jdbc.query("""
                SELECT c.id, c.project_id, c.team_id, c.worker_id, c.task_id, c.status, c.created_at, c.updated_at,
                       c.tenant_id, c.actor_subject, c.version
                  FROM conversation_sessions c
                  JOIN projects p ON p.tenant_id = c.tenant_id
                                 AND (p.id::text = c.project_id OR p.name = c.project_id)
                 WHERE c.tenant_id = ? AND p.id::text = ? AND c.actor_subject = ?
                   AND (c.updated_at < ? OR (c.updated_at = ? AND c.id < ?))
                ORDER BY c.updated_at DESC, c.id DESC
                 LIMIT ?
                """, this::mapSession, tenantId, projectId, actorSubject,
                Timestamp.from(beforeUpdatedAt), Timestamp.from(beforeUpdatedAt), beforeId, limit);
    }

    @Override
    public ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt) {
        ConversationRecord current = findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound);
        return updateStatus(sessionId, status, updatedAt, current.version());
    }

    @Override
    public ConversationRecord updateStatus(UUID sessionId, ConversationService.Status status, Instant updatedAt,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE conversation_sessions
                   SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, status.name(), Timestamp.from(updatedAt), sessionId, expectedVersion);
        if (updated == 0) {
            long actual = findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound).version();
            throw new ConversationVersionConflictException(sessionId, expectedVersion, actual);
        }
        return findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound);
    }

    @Override
    public Optional<MessageRecord> findMessage(UUID sessionId, String idempotencyKey) {
        return jdbc.query("""
                SELECT session_id, idempotency_key, content, start_cursor, end_cursor, created_at, message_status
                  FROM conversation_messages WHERE session_id = ? AND idempotency_key = ?
                """, this::mapMessage, sessionId, idempotencyKey).stream().findFirst();
    }

    @Override
    public List<MessageRecord> findMessages(UUID sessionId) {
        return jdbc.query("""
                SELECT session_id, idempotency_key, content, start_cursor, end_cursor, created_at, message_status
                  FROM conversation_messages WHERE session_id = ? ORDER BY created_at, idempotency_key
                """, this::mapMessage, sessionId);
    }

    @Override
    @Transactional
    public MessageReservation reserveMessage(UUID sessionId, long expectedVersion, MessageRecord message) {
        ConversationRecord current = findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound);
        MessageRecord existing = findMessage(sessionId, message.idempotencyKey()).orElse(null);
        if (existing != null) return new MessageReservation(current, existing, false);
        if (current.status() == ConversationService.Status.CANCELLED) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.CANCELLED,
                    "conversation has been cancelled");
        }
        if (current.version() != expectedVersion) {
            throw new ConversationVersionConflictException(sessionId, expectedVersion, current.version());
        }
        long startCursor = jdbc.query("""
                SELECT COALESCE(MAX(cursor), 0) FROM conversation_events WHERE session_id = ?
                """, (rs, row) -> rs.getLong(1), sessionId).stream().findFirst().orElse(0L);
        MessageRecord reserved = new MessageRecord(sessionId, message.idempotencyKey(), message.content(), startCursor,
                null, message.createdAt(), MessageStatus.RESERVED);
        int inserted = jdbc.update("""
                INSERT INTO conversation_messages
                    (id, session_id, idempotency_key, content, start_cursor, created_at, message_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), sessionId, reserved.idempotencyKey(), reserved.content(), reserved.startCursor(),
                Timestamp.from(reserved.createdAt()), reserved.status().name());
        if (inserted == 0) {
            return new MessageReservation(findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound),
                    findMessage(sessionId, message.idempotencyKey()).orElseThrow(), false);
        }
        int updated = jdbc.update("""
                UPDATE conversation_sessions SET status = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ?
                """, ConversationService.Status.ACTIVE.name(), Timestamp.from(message.createdAt()), sessionId,
                expectedVersion);
        if (updated == 0) {
            long actual = findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound).version();
            throw new ConversationVersionConflictException(sessionId, expectedVersion, actual);
        }
        return new MessageReservation(findSession(sessionId).orElseThrow(ConversationRuntimeException::sessionNotFound),
                reserved, true);
    }

    @Override
    public MessageRecord saveMessage(UUID sessionId, String idempotencyKey, String content, long startCursor,
            Instant createdAt) {
        int inserted = jdbc.update("""
                INSERT INTO conversation_messages
                    (id, session_id, idempotency_key, content, start_cursor, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), sessionId, idempotencyKey, content, startCursor, Timestamp.from(createdAt));
        if (inserted == 0) {
            return findMessage(sessionId, idempotencyKey)
                    .orElseThrow(() -> new DuplicateKeyException("conversation message disappeared"));
        }
        return findMessage(sessionId, idempotencyKey).orElseThrow();
    }

    @Override
    public MessageRecord updateMessageEnd(UUID sessionId, String idempotencyKey, long endCursor) {
        jdbc.update("""
                UPDATE conversation_messages SET end_cursor = ?, message_status = 'COMPLETED'
                 WHERE session_id = ? AND idempotency_key = ?
                """, endCursor, sessionId, idempotencyKey);
        return findMessage(sessionId, idempotencyKey)
                .orElseThrow(ConversationRuntimeException::sessionNotFound);
    }

    @Override
    public MessageRecord updateMessageStatus(UUID sessionId, String idempotencyKey, MessageStatus status) {
        jdbc.update("""
                UPDATE conversation_messages SET message_status = ?
                 WHERE session_id = ? AND idempotency_key = ?
                """, status.name(), sessionId, idempotencyKey);
        return findMessage(sessionId, idempotencyKey)
                .orElseThrow(ConversationRuntimeException::sessionNotFound);
    }

    @Override
    public List<ConversationEvent> findEvents(UUID sessionId, long afterCursor) {
        return jdbc.query("""
                SELECT session_id, cursor, event_type, payload, occurred_at, source_event_id
                  FROM conversation_events WHERE session_id = ? AND cursor > ? ORDER BY cursor
                """, this::mapEvent, sessionId, afterCursor);
    }

    @Override
    public List<ConversationEvent> findEvents(UUID sessionId) {
        return jdbc.query("""
                SELECT session_id, cursor, event_type, payload, occurred_at, source_event_id
                  FROM conversation_events WHERE session_id = ? ORDER BY cursor
                """, this::mapEvent, sessionId);
    }

    @Override
    public ConversationEvent appendEvent(UUID sessionId, ConversationEvent event) {
        Long cursor = jdbc.query("SELECT nextval('conversation_event_cursor_seq')",
                (rs, row) -> rs.getLong(1)).stream().findFirst().orElseThrow();
        int inserted = jdbc.update("""
                INSERT INTO conversation_events (session_id, cursor, event_type, payload, occurred_at, source_event_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id, source_event_id) WHERE source_event_id IS NOT NULL DO NOTHING
                """, sessionId, cursor, event.type(), event.data(), Timestamp.from(event.occurredAt()),
                event.sourceEventId());
        if (inserted == 0 && event.sourceEventId() != null) {
            ConversationEvent existing = jdbc.query("""
                    SELECT session_id, cursor, event_type, payload, occurred_at, source_event_id
                      FROM conversation_events WHERE session_id = ? AND source_event_id = ?
                    """, this::mapEvent, sessionId, event.sourceEventId()).stream().findFirst()
                    .orElseThrow(() -> new DuplicateKeyException("conversation event disappeared"));
            if (!existing.type().equals(event.type()) || !existing.data().equals(event.data())) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                        "source event id was reused with different payload");
            }
            return existing;
        }
        return ConversationEvent.of(sessionId, cursor, event.type(), event.data(), event.occurredAt(),
                event.sourceEventId());
    }

    private ConversationRecord mapSession(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String tenantId = rs.getString("tenant_id");
        String actorSubject = rs.getString("actor_subject");
        ConversationOwner owner = tenantId == null || actorSubject == null ? null
                : new ConversationOwner(tenantId, actorSubject);
        return new ConversationRecord(new ConversationRuntimePort.Context(rs.getString("project_id"),
                rs.getString("team_id"), rs.getString("worker_id"), rs.getString("task_id"), id),
                ConversationService.Status.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "updated_at"), owner, rs.getLong("version"));
    }

    private MessageRecord mapMessage(ResultSet rs, int row) throws SQLException {
        return new MessageRecord(rs.getObject("session_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("content"), rs.getLong("start_cursor"),
                rs.getObject("end_cursor", Long.class), instant(rs, "created_at"),
                MessageStatus.valueOf(rs.getString("message_status")));
    }

    private ConversationEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return ConversationEvent.of(rs.getObject("session_id", UUID.class), rs.getLong("cursor"),
                rs.getString("event_type"), rs.getString("payload"), instant(rs, "occurred_at"),
                rs.getString("source_event_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
}
