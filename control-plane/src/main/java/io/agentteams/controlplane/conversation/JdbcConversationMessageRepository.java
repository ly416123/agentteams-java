package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL persistence for tenant-scoped conversation messages. */
@Repository
public final class JdbcConversationMessageRepository implements ConversationMessageRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcConversationMessageRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcConversationMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public ConversationMessageRecord appendMessage(ExecutionContext context, AppendConversationMessageCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        Long version = lockConversation(context, command.conversationId());
        if (version == null) {
            throw new IllegalArgumentException("conversation is not in the requested scope: " + command.conversationId());
        }

        if (command.idempotencyKey() != null) {
            Optional<ConversationMessageRecord> existing = findByIdempotency(context,
                    command.conversationId(), command.idempotencyKey());
            if (existing.isPresent()) {
                if (!sameRequest(existing.get(), command)) {
                    throw new IdempotencyConflictException(command.idempotencyKey(), "conversation message");
                }
                return existing.get();
            }
        }

        long nextSequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                  FROM conversation_messages_cp
                 WHERE conversation_id = ?
                """, Long.class, command.conversationId());
        String insert = """
                INSERT INTO conversation_messages_cp
                    (id, conversation_id, tenant_id, sender_type, sender_id, channel, message_type,
                     content, content_ref, visibility, occurred_at, sequence, idempotency_key, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        if (command.idempotencyKey() != null) {
            insert += " ON CONFLICT (conversation_id, idempotency_key) DO NOTHING";
        }
        int inserted = jdbc.update(insert, command.id(), command.conversationId(), context.tenantId(),
                command.senderType(), command.senderId(), command.channel(), command.messageType(), command.content(),
                command.contentRef(), command.visibility(), JdbcSupport.timestamp(command.occurredAt()), nextSequence,
                command.idempotencyKey(), command.traceId());
        if (inserted == 0 && command.idempotencyKey() != null) {
            return findByIdempotency(context, command.conversationId(), command.idempotencyKey())
                    .filter(existing -> sameRequest(existing, command))
                    .orElseThrow(() -> new IdempotencyConflictException(command.idempotencyKey(), "conversation message"));
        }
        jdbc.update("""
                UPDATE conversations
                   SET version = version + 1, updated_at = ?
                 WHERE id = ? AND version = ?
                """, JdbcSupport.timestamp(command.occurredAt()), command.conversationId(), version);
        return findById(context, command.conversationId(), command.id()).orElseThrow();
    }

    @Override
    public Optional<ConversationMessageRecord> findByIdempotency(ExecutionContext context,
            UUID conversationId, String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(conversationId, "conversationId");
        if (idempotencyKey == null) return Optional.empty();
        return jdbc.query(selectSql() + " AND m.idempotency_key = ?", this::map, conversationId,
                context.tenantId(), context.projectId(), context.teamId(), idempotencyKey).stream().findFirst();
    }

    @Override
    public List<ConversationMessageRecord> messages(ExecutionContext context, UUID conversationId,
            long after, int limit) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(conversationId, "conversationId");
        if (after < 0) throw new IllegalArgumentException("after must not be negative");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return List.copyOf(jdbc.query(selectSql() + " AND m.sequence > ? ORDER BY m.sequence LIMIT ?", this::map,
                conversationId, context.tenantId(), context.projectId(), context.teamId(), after, limit));
    }

    private Long lockConversation(ExecutionContext context, UUID conversationId) {
        return jdbc.query("""
                SELECT version
                  FROM conversations
                 WHERE id = ? AND tenant_id = ? AND project_id = ?
                   AND team_id IS NOT DISTINCT FROM ?
                 FOR UPDATE
                """, (rs, row) -> rs.getLong("version"), conversationId, context.tenantId(),
                context.projectId(), context.teamId()).stream().findFirst().orElse(null);
    }

    private Optional<ConversationMessageRecord> findById(ExecutionContext context, UUID conversationId, UUID id) {
        return jdbc.query(selectSql() + " AND m.id = ?", this::map, conversationId, context.tenantId(),
                context.projectId(), context.teamId(), id).stream().findFirst();
    }

    private static String selectSql() {
        return """
                SELECT m.id, m.conversation_id, m.tenant_id, m.sender_type, m.sender_id, m.channel,
                       m.message_type, m.content, m.content_ref, m.visibility, m.occurred_at,
                       m.sequence, m.idempotency_key, m.trace_id
                  FROM conversation_messages_cp m
                  JOIN conversations c ON c.id = m.conversation_id
                 WHERE m.conversation_id = ? AND m.tenant_id = ? AND c.project_id = ?
                   AND c.team_id IS NOT DISTINCT FROM ?
                """;
    }

    private ConversationMessageRecord map(ResultSet rs, int row) throws SQLException {
        return new ConversationMessageRecord(rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("tenant_id"),
                rs.getString("sender_type"), rs.getString("sender_id"), rs.getString("channel"),
                rs.getString("message_type"), rs.getString("content"), rs.getString("content_ref"),
                rs.getString("visibility"), JdbcSupport.instant(rs, "occurred_at"), rs.getLong("sequence"),
                rs.getString("idempotency_key"), rs.getString("trace_id"));
    }

    private static boolean sameRequest(ConversationMessageRecord existing,
            AppendConversationMessageCommand command) {
        return Objects.equals(existing.senderType(), command.senderType())
                && Objects.equals(existing.senderId(), command.senderId())
                && Objects.equals(existing.channel(), command.channel())
                && Objects.equals(existing.messageType(), command.messageType())
                && Objects.equals(existing.content(), command.content())
                && Objects.equals(existing.contentRef(), command.contentRef())
                && Objects.equals(existing.visibility(), command.visibility())
                && Objects.equals(existing.occurredAt(), command.occurredAt())
                && Objects.equals(existing.traceId(), command.traceId());
    }
}
