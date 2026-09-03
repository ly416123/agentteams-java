package io.agentteams.manager.security;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves Manager project names/UUIDs to the same canonical UUID before conversation access. */
@Service
public final class ManagerProjectScopeResolver {
    private final JdbcTemplate jdbc;

    public ManagerProjectScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public ManagerPrincipal canonicalize(ManagerPrincipal principal, String requestedProject) {
        Objects.requireNonNull(principal, "principal");
        String value = requestedProject == null || requestedProject.isBlank()
                ? principal.projectId() : requestedProject.trim();
        String projectId = jdbc.query("""
                SELECT p.id::text
                  FROM projects p
                 WHERE p.tenant_id = ? AND p.status = 'ACTIVE'
                   AND (p.id::text = ? OR p.name = ?)
                   AND EXISTS (SELECT 1 FROM project_memberships m
                                WHERE m.tenant_id = p.tenant_id AND m.project_id = p.id
                                  AND m.subject = ? AND m.status = 'ACTIVE')
                """, (rs, row) -> rs.getString(1), principal.tenantId(), value, value,
                principal.subject()).stream().findFirst()
                .orElseThrow(() -> new ManagerAuthorizationException("project scope not found"));
        return new ManagerPrincipal(principal.subject(), principal.tenantId(), projectId, principal.teamId(),
                principal.permissions());
    }
}
