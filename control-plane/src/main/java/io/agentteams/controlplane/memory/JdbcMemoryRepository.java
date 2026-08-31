package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL memory metadata store with mandatory organization and tenant predicates. */
@Repository
public class JdbcMemoryRepository implements MemoryRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcMemoryRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public MemoryRecord save(MemoryRecord memory) {
        jdbc.update("""
                INSERT INTO memories
                    (id, organization_id, tenant_id, project_id, team_id, subject_id, scope, content_ref,
                     summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET summary = EXCLUDED.summary, consent_status = EXCLUDED.consent_status,
                    sensitivity = EXCLUDED.sensitivity, expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at, version = memories.version + 1
                """, memory.id(), memory.policy().organizationId(), memory.policy().tenantId(),
                memory.policy().projectId(), memory.policy().teamId(), memory.policy().subjectId(),
                memory.policy().scope().name(), memory.contentRef(), memory.summary(), memory.policy().sensitivity().name(),
                memory.policy().consent().name(), memory.source(), memory.policy().retention().toSeconds(),
                nullableTimestamp(memory.expiresAt()),
                JdbcSupport.timestamp(memory.createdAt()), JdbcSupport.timestamp(memory.updatedAt()), memory.version());
        return memory;
    }

    @Override
    public List<MemoryRecord> find(String organizationId, String tenantId) {
        return jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, team_id, subject_id, scope, content_ref,
                       summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version
                  FROM memories
                 WHERE organization_id = ? AND tenant_id = ?
                 ORDER BY updated_at DESC, id
                """, this::map, organizationId, tenantId);
    }

    private MemoryRecord map(ResultSet rs, int row) throws SQLException {
        MemoryPolicy policy = new MemoryPolicy(MemoryPolicy.Scope.valueOf(rs.getString("scope")),
                rs.getString("organization_id"), rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("team_id"), rs.getString("subject_id"),
                MemoryPolicy.Sensitivity.valueOf(rs.getString("sensitivity")),
                MemoryPolicy.Consent.valueOf(rs.getString("consent_status")),
                java.time.Duration.ofSeconds(rs.getLong("retention_seconds")));
        return new MemoryRecord(rs.getObject("id", UUID.class), policy, rs.getString("content_ref"),
                rs.getString("summary"), rs.getString("source"), nullableInstant(rs, "expires_at"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private static java.sql.Timestamp nullableTimestamp(java.time.Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static java.time.Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
