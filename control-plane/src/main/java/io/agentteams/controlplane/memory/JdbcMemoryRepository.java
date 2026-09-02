package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.persistence.JdbcSupport;
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

/** PostgreSQL memory metadata store with mandatory organization and tenant predicates. */
@Repository
public class JdbcMemoryRepository implements MemoryRepository, MemoryGovernanceRepository {
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
                    (id, organization_id, tenant_id, project_id, team_id, task_id, subject_id, scope, content_ref,
                     summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version,
                     governance_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET summary = EXCLUDED.summary, consent_status = EXCLUDED.consent_status,
                    sensitivity = EXCLUDED.sensitivity, expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at, version = EXCLUDED.version,
                    governance_status = EXCLUDED.governance_status
                """, memory.id(), memory.policy().organizationId(), memory.policy().tenantId(),
                memory.policy().projectId(), memory.policy().teamId(), nullableTaskId(memory.policy().taskId()),
                memory.policy().subjectId(),
                memory.policy().scope().name(), memory.contentRef(), memory.summary(), memory.policy().sensitivity().name(),
                memory.policy().consent().name(), memory.source(), memory.policy().retention().toSeconds(),
                nullableTimestamp(memory.expiresAt()),
                JdbcSupport.timestamp(memory.createdAt()), JdbcSupport.timestamp(memory.updatedAt()), memory.version(),
                memory.governanceStatus().name());
        return memory;
    }

    @Override
    public List<MemoryRecord> find(String organizationId, String tenantId) {
        return jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, team_id, task_id, subject_id, scope, content_ref,
                       summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version,
                       governance_status
                  FROM memories
                 WHERE organization_id = ? AND tenant_id = ?
                 ORDER BY updated_at DESC, id
                """, this::map, organizationId, tenantId);
    }

    @Override
    public List<MemoryRecord> find(String organizationId, String tenantId, String projectId) {
        return jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, team_id, task_id, subject_id, scope, content_ref,
                       summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version,
                       governance_status
                  FROM memories
                 WHERE organization_id = ? AND tenant_id = ?
                   AND (project_id = ? OR project_id IS NULL)
                 ORDER BY updated_at DESC, id
                """, this::map, organizationId, tenantId, projectId);
    }

    @Override
    public Optional<MemoryRecord> findById(UUID memoryId, String organizationId, String tenantId) {
        return jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, team_id, task_id, subject_id, scope, content_ref,
                       summary, sensitivity, consent_status, source, retention_seconds, expires_at, created_at, updated_at, version,
                       governance_status
                  FROM memories
                 WHERE id = ? AND organization_id = ? AND tenant_id = ?
                """, this::map, memoryId, organizationId, tenantId).stream().findFirst();
    }

    @Override
    public Optional<MemoryGovernanceOperation> findOperation(String idempotencyKey) {
        return jdbc.query("""
                SELECT id, memory_id, organization_id, tenant_id, operation, reason, actor, idempotency_key, created_at
                  FROM memory_governance_operations WHERE idempotency_key = ?
                """, (rs, row) -> new MemoryGovernanceOperation(rs.getObject("id", UUID.class),
                        rs.getObject("memory_id", UUID.class), rs.getString("organization_id"),
                        rs.getString("tenant_id"), rs.getString("operation"), rs.getString("reason"),
                        rs.getString("actor"), rs.getString("idempotency_key"),
                        JdbcSupport.instant(rs, "created_at")), idempotencyKey).stream().findFirst();
    }

    @Override
    public MemoryGovernanceOperation recordOperation(MemoryGovernanceOperation operation) {
        jdbc.update("""
                INSERT INTO memory_governance_operations
                    (id, memory_id, organization_id, tenant_id, operation, reason, actor, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, operation.id(), operation.memoryId(), operation.organizationId(), operation.tenantId(),
                operation.operation(), operation.reason(), operation.actor(), operation.idempotencyKey(),
                JdbcSupport.timestamp(operation.createdAt()));
        return findOperation(operation.idempotencyKey()).orElseThrow(() ->
                new IllegalStateException("memory governance operation was not persisted"));
    }

    private MemoryRecord map(ResultSet rs, int row) throws SQLException {
        MemoryPolicy policy = new MemoryPolicy(MemoryPolicy.Scope.valueOf(rs.getString("scope")),
                rs.getString("organization_id"), rs.getString("tenant_id"), rs.getString("project_id"),
                rs.getString("team_id"), rs.getString("subject_id"), taskId(rs),
                MemoryPolicy.Sensitivity.valueOf(rs.getString("sensitivity")),
                MemoryPolicy.Consent.valueOf(rs.getString("consent_status")),
                java.time.Duration.ofSeconds(rs.getLong("retention_seconds")));
        return new MemoryRecord(rs.getObject("id", UUID.class), policy, rs.getString("content_ref"),
                rs.getString("summary"), rs.getString("source"), nullableInstant(rs, "expires_at"),
                JdbcSupport.instant(rs, "created_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"),
                MemoryRecord.GovernanceStatus.valueOf(rs.getString("governance_status")));
    }

    private static java.sql.Timestamp nullableTimestamp(java.time.Instant value) {
        return value == null ? null : JdbcSupport.timestamp(value);
    }

    private static UUID nullableTaskId(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static String taskId(ResultSet rs) throws SQLException {
        UUID value = rs.getObject("task_id", UUID.class);
        return value == null ? null : value.toString();
    }

    private static java.time.Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
