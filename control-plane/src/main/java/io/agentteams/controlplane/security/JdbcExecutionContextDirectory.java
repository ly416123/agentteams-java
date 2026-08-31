package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Resolves legacy tenant identifiers only when the subject belongs to both org and tenant. */
@Repository
public final class JdbcExecutionContextDirectory implements ExecutionContextResolver.ScopeDirectory {
    private final JdbcTemplate jdbc;

    public JdbcExecutionContextDirectory(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ExecutionContext> resolve(String legacyTenantId, String projectId, String teamId,
            String subjectId) {
        requireText(legacyTenantId, "legacyTenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
        requireText(subjectId, "subjectId");
        return jdbc.query("""
                SELECT organization.id::text AS organization_id, tenant.id::text AS tenant_id
                  FROM legacy_tenant_mappings mapping
                  JOIN organizations organization ON organization.id = mapping.organization_id
                  JOIN tenants tenant ON tenant.organization_id = mapping.organization_id
                                          AND tenant.id = mapping.tenant_id
                 WHERE (mapping.legacy_tenant_key = ? OR tenant.id::text = ? OR tenant.external_key = ?)
                   AND organization.status = 'ACTIVE' AND tenant.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM organization_memberships organization_member
                                WHERE organization_member.organization_id = organization.id
                                  AND organization_member.subject = ?)
                   AND EXISTS (SELECT 1 FROM tenant_memberships tenant_member
                                WHERE tenant_member.tenant_id = tenant.id
                                  AND tenant_member.subject = ?)
                """, (rs, row) -> new ExecutionContext(rs.getString("organization_id"),
                rs.getString("tenant_id"), projectId, teamId, subjectId), legacyTenantId, legacyTenantId,
                legacyTenantId, subjectId, subjectId).stream().findFirst();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
