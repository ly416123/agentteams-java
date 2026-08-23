package io.agentteams.controlplane.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists ownership for resources whose domain tables are intentionally scope-neutral. */
@Repository
public final class ResourceScopeRepository {
    private final JdbcTemplate jdbc;

    public ResourceScopeRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void bind(String resourceType, UUID resourceId, Principal principal, Instant createdAt) {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(principal, "principal");
        jdbc.update("""
                INSERT INTO resource_scopes(resource_type, resource_id, tenant_id, project_id, team,
                                             created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (resource_type, resource_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
                """, resourceType, resourceId, principal.scope().tenant(), principal.scope().project(),
                principal.scope().team(), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    public boolean visible(String resourceType, UUID resourceId) {
        return PrincipalContext.current().map(principal -> jdbc.query("""
                SELECT 1 FROM resource_scopes
                 WHERE resource_type = ? AND resource_id = ?
                   AND tenant_id = ? AND project_id = ? AND team = ?
                """, (rs, row) -> true, resourceType, resourceId, principal.scope().tenant(),
                principal.scope().project(), principal.scope().team()).stream().findFirst().orElse(false)).orElse(true);
    }

    public void requireVisible(String resourceType, UUID resourceId) {
        if (!visible(resourceType, resourceId)) {
            throw new AuthorizationException("resource is outside caller project");
        }
    }
}
