package io.agentteams.manager.security;

import java.util.Objects;
import java.util.Set;

/** Verified caller context; HTTP request fields never define these values. */
public record ManagerPrincipal(String subject, String tenantId, String projectId, String teamId,
        Set<String> permissions) {
    public ManagerPrincipal {
        requireText(subject, "subject");
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        requireText(teamId, "teamId");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
