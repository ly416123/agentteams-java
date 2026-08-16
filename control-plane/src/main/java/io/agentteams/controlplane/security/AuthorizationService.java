package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.Set;

public final class AuthorizationService {
    public void require(String actor, Permission permission, Set<String> grantedPermissions) {
        if (actor == null || actor.isBlank()) throw new AuthorizationException("actor is required");
        Objects.requireNonNull(permission, "permission");
        if (grantedPermissions == null || !grantedPermissions.contains(permission.value())) {
            throw new AuthorizationException("permission denied: " + permission.value());
        }
    }

    public void requireScope(Scope principal, Scope resource) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(resource, "resource");
        if (!principal.tenant().equals(resource.tenant())
                || !principal.project().equals(resource.project())
                || !principal.team().equals(resource.team())) {
            throw new AuthorizationException("resource is outside the caller scope");
        }
    }

    public record Scope(String tenant, String project, String team) {
        public Scope {
            if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("tenant is required");
            if (project == null || project.isBlank()) throw new IllegalArgumentException("project is required");
            if (team == null || team.isBlank()) throw new IllegalArgumentException("team is required");
        }
    }
}
