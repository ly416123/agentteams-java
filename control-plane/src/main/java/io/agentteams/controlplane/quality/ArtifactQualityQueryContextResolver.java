package io.agentteams.controlplane.quality;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.ProjectScope;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves a read-only ledger scope after project membership authorization.
 * Unlike execution writes, reading project records does not require separate
 * organization and tenant memberships.
 */
@Repository
public class ArtifactQualityQueryContextResolver {
    private final JdbcTemplate jdbc;

    public ArtifactQualityQueryContextResolver(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public ExecutionContext resolve(Principal principal, ProjectScope scope) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(scope, "scope");
        List<ExecutionContext> contexts = jdbc.query("""
                SELECT organization.id::text AS organization_id, tenant.id::text AS tenant_id,
                       project.id::text AS project_id
                  FROM projects project
                  JOIN tenants tenant ON tenant.external_key = project.tenant_id
                  JOIN organizations organization ON organization.id = tenant.organization_id
                 WHERE project.id::text = ? AND project.tenant_id = ?
                   AND project.status = 'ACTIVE' AND tenant.status = 'ACTIVE'
                   AND organization.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM project_memberships project_member
                                WHERE project_member.project_id = project.id
                                  AND project_member.tenant_id = project.tenant_id
                                  AND project_member.subject = ?
                                  AND project_member.status = 'ACTIVE')
                """, (rs, row) -> new ExecutionContext(rs.getString("organization_id"), rs.getString("tenant_id"),
                rs.getString("project_id"), scope.teamId(), principal.subject()),
                scope.projectIdValue(), scope.tenantId(), principal.subject());
        return contexts.stream().findFirst()
                .orElseThrow(() -> new AuthorizationException("project ledger scope is unavailable"));
    }
}
