package io.agentteams.controlplane.organization;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcManagementRoleRepository implements ManagementRoleRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcManagementRoleRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcManagementRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean hasRole(String subject, String scopeType, UUID organizationId, String role) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM management_role_assignments
                 WHERE subject = ? AND scope_type = ? AND organization_id IS NOT DISTINCT FROM ?::uuid
                   AND role = ? AND status = 'ACTIVE'
                """, Integer.class, subject, scopeType, organizationId == null ? null : organizationId.toString(), role);
        return count != null && count > 0;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean bootstrapPlatformAdminIfAbsent(String subject, String actor, Instant now) {
        jdbc.execute("SELECT pg_advisory_xact_lock(821346731245901)");
        if (hasAnyPlatformAdmin()) return false;
        upsert(subject, "PLATFORM", null, "PLATFORM_ADMIN", actor, now);
        return true;
    }

    @Override
    public boolean hasAnyPlatformAdmin() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM management_role_assignments
                 WHERE scope_type = 'PLATFORM' AND role = 'PLATFORM_ADMIN' AND status = 'ACTIVE'
                """, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<ManagementRoleAssignment> listByScope(String scopeType, UUID organizationId) {
        return jdbc.query("""
                SELECT id, subject, scope_type, organization_id, role, status, version
                  FROM management_role_assignments
                 WHERE scope_type = ? AND organization_id IS NOT DISTINCT FROM ?::uuid AND status = 'ACTIVE'
                 ORDER BY subject
                """, (rs, row) -> new ManagementRoleAssignment(rs.getObject("id", UUID.class),
                rs.getString("subject"), rs.getString("scope_type"),
                rs.getObject("organization_id", UUID.class), rs.getString("role"), rs.getString("status"),
                rs.getLong("version")), scopeType,
                organizationId == null ? null : organizationId.toString());
    }

    @Override
    public List<ManagementRoleAssignment> list(String subject) {
        return jdbc.query("""
                SELECT id, subject, scope_type, organization_id, role, status, version
                  FROM management_role_assignments WHERE subject = ? AND status = 'ACTIVE'
                 ORDER BY scope_type, organization_id
                """, (rs, row) -> new ManagementRoleAssignment(rs.getObject("id", UUID.class),
                rs.getString("subject"), rs.getString("scope_type"),
                rs.getObject("organization_id", UUID.class), rs.getString("role"), rs.getString("status"),
                rs.getLong("version")), subject);
    }

    @Override
    public boolean updateStatus(String subject, String scopeType, UUID organizationId, String status,
            long expectedVersion, String actor, Instant now) {
        return jdbc.update("""
                UPDATE management_role_assignments SET status = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE subject = ? AND scope_type = ? AND organization_id IS NOT DISTINCT FROM ?::uuid
                   AND version = ? AND status <> ?
                """, status, actor, java.sql.Timestamp.from(now), subject, scopeType,
                organizationId == null ? null : organizationId.toString(), expectedVersion, status) == 1;
    }

    @Override
    public ManagementRoleAssignment upsert(String subject, String scopeType, UUID organizationId, String role,
            String actor, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO management_role_assignments
                    (id, subject, scope_type, organization_id, role, status, version, created_by, updated_by,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, subject, scopeType, organizationId, role, actor, actor,
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbc.update("""
                UPDATE management_role_assignments SET role = ?, status = 'ACTIVE', updated_by = ?,
                    updated_at = ?, version = version + 1
                 WHERE subject = ? AND scope_type = ? AND organization_id IS NOT DISTINCT FROM ?::uuid
                   AND id <> ?
                """, role, actor, java.sql.Timestamp.from(now), subject, scopeType,
                organizationId == null ? null : organizationId.toString(), id);
        return jdbc.query("""
                SELECT id, subject, scope_type, organization_id, role, status, version
                  FROM management_role_assignments WHERE subject = ? AND scope_type = ?
                    AND organization_id IS NOT DISTINCT FROM ?::uuid
                """, (rs, row) -> new ManagementRoleAssignment(rs.getObject("id", UUID.class),
                rs.getString("subject"), rs.getString("scope_type"), rs.getObject("organization_id", UUID.class),
                rs.getString("role"), rs.getString("status"), rs.getLong("version")), subject, scopeType,
                organizationId == null ? null : organizationId.toString()).stream().findFirst().orElseThrow();
    }
}
