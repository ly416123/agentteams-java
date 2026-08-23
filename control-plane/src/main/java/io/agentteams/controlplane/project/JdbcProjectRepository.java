package io.agentteams.controlplane.project;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectRepository implements ProjectRepository {
    private final JdbcTemplate jdbc;

    public JdbcProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ProjectRecord> findProject(String tenantId, UUID projectId) {
        return jdbc.query("""
                SELECT id, tenant_id, name, status, created_by, created_at, updated_at, version
                  FROM projects WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> new ProjectRecord(rs.getObject("id", UUID.class),
                rs.getString("tenant_id"), rs.getString("name"), rs.getString("status"),
                rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version")), tenantId, projectId)
                .stream().findFirst();
    }

    @Override
    public void insertProject(ProjectRecord project) {
        jdbc.update("""
                INSERT INTO projects(id, tenant_id, name, status, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, project.id(), project.tenantId(), project.name(), project.status(), project.createdBy(),
                timestamp(project.createdAt()), timestamp(project.updatedAt()),
                project.version());
    }

    @Override
    public Optional<ProjectMembershipRecord> findMembership(String tenantId, UUID projectId, String subject) {
        return jdbc.query("""
                SELECT tenant_id, project_id, subject, role, created_at, updated_at, version
                  FROM project_memberships
                 WHERE tenant_id = ? AND project_id = ? AND subject = ?
                """, (rs, row) -> membership(rs), tenantId, projectId, subject).stream().findFirst();
    }

    @Override
    public void upsertMembership(ProjectMembershipRecord membership) {
        jdbc.update("""
                INSERT INTO project_memberships
                    (tenant_id, project_id, subject, role, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, subject) DO UPDATE
                    SET role = EXCLUDED.role, updated_at = EXCLUDED.updated_at,
                        version = project_memberships.version + 1
                """, membership.tenantId(), membership.projectId(), membership.subject(),
                membership.role().name(), timestamp(membership.createdAt()),
                timestamp(membership.updatedAt()), membership.version());
    }

    @Override
    public Optional<ProjectCreateIdempotency> findProjectCreateIdempotency(String tenantId, String key) {
        return jdbc.query("""
                SELECT tenant_id, idempotency_key, request_hash, project_id, created_at
                  FROM project_create_idempotency WHERE tenant_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ProjectCreateIdempotency(rs.getString("tenant_id"),
                rs.getString("idempotency_key"), rs.getString("request_hash"),
                rs.getObject("project_id", UUID.class), instant(rs, "created_at")), tenantId, key)
                .stream().findFirst();
    }

    @Override
    public boolean insertProjectCreateIdempotency(ProjectCreateIdempotency record) {
        return jdbc.update("""
                INSERT INTO project_create_idempotency
                    (tenant_id, idempotency_key, request_hash, project_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, record.tenantId(), record.key(), record.requestHash(), record.projectId(),
                timestamp(record.createdAt())) == 1;
    }

    @Override
    public Optional<ProjectMembershipIdempotency> findMembershipIdempotency(String tenantId, UUID projectId,
            String key) {
        return jdbc.query("""
                SELECT tenant_id, project_id, idempotency_key, request_hash, subject, role, created_at
                  FROM project_membership_idempotency
                 WHERE tenant_id = ? AND project_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ProjectMembershipIdempotency(rs.getString("tenant_id"),
                rs.getObject("project_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getString("subject"),
                ProjectRole.valueOf(rs.getString("role")), instant(rs, "created_at")),
                tenantId, projectId, key).stream().findFirst();
    }

    @Override
    public boolean insertMembershipIdempotency(ProjectMembershipIdempotency record) {
        return jdbc.update("""
                INSERT INTO project_membership_idempotency
                    (tenant_id, project_id, idempotency_key, request_hash, subject, role, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, idempotency_key) DO NOTHING
                """, record.tenantId(), record.projectId(), record.key(), record.requestHash(), record.subject(),
                record.role().name(), timestamp(record.createdAt())) == 1;
    }

    private static ProjectMembershipRecord membership(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectMembershipRecord(rs.getString("tenant_id"), rs.getObject("project_id", UUID.class),
                rs.getString("subject"), ProjectRole.valueOf(rs.getString("role")),
                instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return Timestamp.from(value);
    }

    private static java.time.Instant instant(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return Objects.requireNonNull(value, column + " must not be null").toInstant();
    }
}
