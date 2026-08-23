package io.agentteams.controlplane.agentspec;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the existing resource_scopes ownership table without exposing it as a domain dependency. */
public final class JdbcAgentSpecReferenceVisibility implements AgentSpecReferenceVisibility {

    private final JdbcTemplate jdbc;

    public JdbcAgentSpecReferenceVisibility(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean visible(String resourceType, UUID resourceId, AgentSpecReferenceCatalog.Scope scope) {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (scope == null || scope.tenantId() == null || scope.projectId() == null || scope.teamId() == null) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM resource_scopes
                     WHERE resource_type = ? AND resource_id = ?
                       AND tenant_id = ? AND project_id = ? AND team = ?
                )
                """, Boolean.class, resourceType, resourceId, scope.tenantId(), scope.projectId(),
                scope.teamId()));
    }
}
