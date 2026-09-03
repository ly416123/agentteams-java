package io.agentteams.controlplane.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists ownership for resources whose domain tables are intentionally scope-neutral. */
@Repository
public class ResourceScopeRepository {
    private final JdbcTemplate jdbc;
    private final ProjectScopeResolver projectScopes;

    public ResourceScopeRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public ResourceScopeRepository(JdbcTemplate jdbc, ProjectScopeResolver projectScopes) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.projectScopes = projectScopes;
    }

    public void bind(String resourceType, UUID resourceId, Principal principal, Instant createdAt) {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(principal, "principal");
        int updated = jdbc.update("""
                INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team,
                                             created_at, updated_at)
                SELECT ?, ?, p.tenant_id, p.id::text, ?, ?, ?
                  FROM projects p
                 WHERE p.tenant_id = ? AND (p.id::text = ? OR p.name = ?)
                   AND p.status = 'ACTIVE'
                ON CONFLICT (resource_type, resource_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
                """, resourceType, resourceId, principal.scope().team(), Timestamp.from(createdAt),
                Timestamp.from(createdAt), principal.scope().tenant(), principal.scope().project(),
                principal.scope().project());
        if (updated == 0) throw new AuthorizationException("project scope not found");
    }

    public boolean visible(String resourceType, UUID resourceId) {
        return PrincipalContext.current().map(principal -> jdbc.query("""
                SELECT 1
                  FROM resource_scopes s
                  JOIN projects stored ON stored.tenant_id = s.tenant_id
                                     AND (stored.id::text = s.project_id OR stored.name = s.project_id)
                  JOIN projects caller ON caller.tenant_id = stored.tenant_id
                                     AND (caller.id::text = ? OR caller.name = ?)
                                     AND caller.id = stored.id
                 WHERE s.resource_type = ? AND s.resource_id = ?
                   AND s.tenant_id = ? AND s.team = ?
                   AND stored.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = stored.tenant_id AND m.project_id = stored.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """, (rs, row) -> true, principal.scope().project(), principal.scope().project(),
                resourceType, resourceId, principal.scope().tenant(), principal.scope().team(),
                principal.subject()).stream().findFirst().orElse(false)).orElse(true);
    }

    public void requireVisible(String resourceType, UUID resourceId) {
        if (!visible(resourceType, resourceId)) {
            throw new AuthorizationException("resource is outside caller project");
        }
    }

    /** Accepts either the stable Project UUID used by the Console route or its external name. */
    public boolean matchesCallerProject(String projectId) {
        return PrincipalContext.current().map(principal -> jdbc.query("""
                SELECT 1
                  FROM projects p
                 WHERE p.tenant_id = ?
                   AND (p.id::text = ? OR p.name = ?)
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = p.tenant_id AND m.project_id = p.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """, (rs, row) -> true, principal.scope().tenant(), projectId, projectId,
                principal.subject()).stream().findFirst().orElse(false))
                .orElse(false);
    }

    /** Canonicalizes an explicitly supplied project route when this repository has project metadata. */
    public Principal canonicalize(Principal principal, String requestedProject) {
        if (projectScopes == null) return principal;
        return projectScopes.canonicalize(principal, requestedProject);
    }
}
