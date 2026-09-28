package io.agentteams.controlplane.conversation;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for tenant-scoped conversations. */
@Repository
public final class JdbcConversationRepository implements ConversationRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcConversationRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public ConversationRecord create(ExecutionContext context, CreateConversationCommand command) {
        ConversationRepository.requireContext(context);
        Objects.requireNonNull(command, "command");
        jdbc.update("""
                INSERT INTO conversations
                    (id, tenant_id, project_id, team_id, title, status, created_by,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
                """, command.id(), context.tenantId(), context.projectId(), context.teamId(), command.title(),
                context.subjectId(), JdbcSupport.timestamp(command.createdAt()),
                JdbcSupport.timestamp(command.createdAt()));
        return find(context, command.id()).orElseThrow();
    }

    @Override
    public Optional<ConversationRecord> find(ExecutionContext context, UUID id) {
        ConversationRepository.requireContext(context);
        Objects.requireNonNull(id, "id");
        return jdbc.query("""
                SELECT id, tenant_id, project_id, team_id, title, status, created_by,
                       created_at, updated_at, version
                  FROM conversations
                 WHERE id = ? AND tenant_id = ? AND project_id = ?
                   AND team_id IS NOT DISTINCT FROM ?
                """, this::map, id, context.tenantId(), context.projectId(), context.teamId())
                .stream().findFirst();
    }

    @Override
    public ConversationRecord archive(ExecutionContext context, UUID id, long expectedVersion) {
        ConversationRepository.requireContext(context);
        Objects.requireNonNull(id, "id");
        int updated = jdbc.update("""
                UPDATE conversations
                   SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE id = ? AND tenant_id = ? AND project_id = ?
                   AND team_id IS NOT DISTINCT FROM ? AND version = ?
                """, id, context.tenantId(), context.projectId(), context.teamId(), expectedVersion);
        if (updated == 0) {
            throw new IllegalStateException("conversation version mismatch or conversation is not in scope: " + id);
        }
        return find(context, id).orElseThrow();
    }

    private ConversationRecord map(ResultSet rs, int row) throws SQLException {
        return new ConversationRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("project_id"), rs.getString("team_id"), rs.getString("title"),
                rs.getString("status"), rs.getString("created_by"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
