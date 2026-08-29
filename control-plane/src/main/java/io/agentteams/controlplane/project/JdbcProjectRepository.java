package io.agentteams.controlplane.project;

import io.agentteams.controlplane.api.CursorPageRequest;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
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
    public Optional<ProjectRecord> findProjectByName(String tenantId, String name) {
        return jdbc.query("""
                SELECT id, tenant_id, name, status, created_by, created_at, updated_at, version
                  FROM projects WHERE tenant_id = ? AND name = ?
                """, (rs, row) -> new ProjectRecord(rs.getObject("id", UUID.class),
                rs.getString("tenant_id"), rs.getString("name"), rs.getString("status"),
                rs.getString("created_by"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version")), tenantId, name)
                .stream().findFirst();
    }

    @Override
    public List<ProjectRecord> findProjects(String tenantId, String actor, CursorPageRequest.Position after, int limit,
            CursorPageRequest.Direction direction) {
        String order = direction == CursorPageRequest.Direction.ASC
                ? " ORDER BY p.updated_at ASC, p.id ASC LIMIT ?"
                : " ORDER BY p.updated_at DESC, p.id DESC LIMIT ?";
        String cursorClause = after == null ? "" : direction == CursorPageRequest.Direction.ASC
                ? " AND (p.updated_at, p.id) > (?, ?)" : " AND (p.updated_at, p.id) < (?, ?)";
        String sql = """
                SELECT p.id, p.tenant_id, p.name, p.status, p.created_by, p.created_at, p.updated_at, p.version
                  FROM projects p
                 WHERE p.tenant_id = ?
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = p.tenant_id AND m.project_id = p.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """ + cursorClause + order;
        if (after == null) return jdbc.query(sql, this::mapProject, tenantId, actor, limit);
        return jdbc.query(sql, this::mapProject, tenantId, actor, Timestamp.from(after.updatedAt()), after.id(), limit);
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
                SELECT tenant_id, project_id, subject, role, status, created_at, updated_at, version
                  FROM project_memberships
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE'
                """, (rs, row) -> membership(rs), tenantId, projectId, subject).stream().findFirst();
    }

    @Override
    public Optional<ProjectMembershipRecord> findMembershipIncludingInactive(String tenantId, UUID projectId,
            String subject) {
        return jdbc.query("""
                SELECT tenant_id, project_id, subject, role, status, created_at, updated_at, version
                  FROM project_memberships
                 WHERE tenant_id = ? AND project_id = ? AND subject = ?
                """, (rs, row) -> membership(rs), tenantId, projectId, subject).stream().findFirst();
    }

    @Override
    public List<ProjectMembershipRecord> findMemberships(String tenantId, UUID projectId) {
        return jdbc.query("""
                SELECT tenant_id, project_id, subject, role, status, created_at, updated_at, version
                  FROM project_memberships WHERE tenant_id = ? AND project_id = ?
                 ORDER BY created_at, subject
                """, (rs, row) -> membership(rs), tenantId, projectId);
    }

    @Override
    public void upsertMembership(ProjectMembershipRecord membership) {
        jdbc.update("""
                INSERT INTO project_memberships
                    (tenant_id, project_id, subject, role, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, subject) DO UPDATE
                    SET role = EXCLUDED.role, status = EXCLUDED.status, updated_at = EXCLUDED.updated_at,
                        version = project_memberships.version + 1
                """, membership.tenantId(), membership.projectId(), membership.subject(),
                membership.role().name(), membership.status(), timestamp(membership.createdAt()),
                timestamp(membership.updatedAt()), membership.version());
    }

    @Override
    public boolean deactivateMembership(String tenantId, UUID projectId, String subject, java.time.Instant updatedAt) {
        return jdbc.update("""
                UPDATE project_memberships SET status = 'INACTIVE', updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE'
                """, timestamp(updatedAt), tenantId, projectId, subject) == 1;
    }

    @Override
    public int countActiveOwners(String tenantId, UUID projectId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM project_memberships
                 WHERE tenant_id = ? AND project_id = ? AND status = 'ACTIVE' AND role = 'OWNER'
                """, Integer.class, tenantId, projectId);
        return count == null ? 0 : count;
    }

    @Override
    public boolean transferOwnership(String tenantId, UUID projectId, String currentOwner, String newOwner,
            long expectedProjectVersion, java.time.Instant updatedAt) {
        int projectUpdated = jdbc.update("""
                UPDATE projects SET version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                   AND EXISTS (SELECT 1 FROM project_memberships
                                WHERE tenant_id = ? AND project_id = ? AND subject = ?
                                  AND status = 'ACTIVE' AND role = 'OWNER')
                   AND EXISTS (SELECT 1 FROM project_memberships
                                WHERE tenant_id = ? AND project_id = ? AND subject = ?
                                  AND status = 'ACTIVE')
                """, timestamp(updatedAt), tenantId, projectId, expectedProjectVersion,
                tenantId, projectId, currentOwner, tenantId, projectId, newOwner);
        if (projectUpdated != 1) return false;
        int demoted = jdbc.update("""
                UPDATE project_memberships SET role = 'ADMIN', updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE' AND role = 'OWNER'
                """, timestamp(updatedAt), tenantId, projectId, currentOwner);
        int promoted = jdbc.update("""
                UPDATE project_memberships SET role = 'OWNER', updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE' AND role <> 'OWNER'
                """, timestamp(updatedAt), tenantId, projectId, newOwner);
        return demoted == 1 && promoted == 1;
    }

    @Override
    public boolean updateMembershipStatus(String tenantId, UUID projectId, String subject, String status,
            long expectedVersion, java.time.Instant updatedAt) {
        return jdbc.update("""
                UPDATE project_memberships SET status = ?, updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND version = ?
                """, status, timestamp(updatedAt), tenantId, projectId, subject, expectedVersion) == 1;
    }

    @Override
    public boolean updateMembershipRole(String tenantId, UUID projectId, String subject, ProjectRole role,
            long expectedVersion, java.time.Instant updatedAt) {
        return jdbc.update("""
                UPDATE project_memberships SET role = ?, updated_at = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND subject = ? AND status = 'ACTIVE' AND version = ?
                """, role.name(), timestamp(updatedAt), tenantId, projectId, subject, expectedVersion) == 1;
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
                rs.getString("subject"), ProjectRole.valueOf(rs.getString("role")), rs.getString("status"),
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

    private ProjectRecord mapProject(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ProjectRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                rs.getString("name"), rs.getString("status"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getLong("version"));
    }
}
