package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.UUID;

/** Canonical project scope used after resolving an external project name or UUID. */
public record ProjectScope(String tenantId, UUID projectId, String projectName, String teamId) {
    public ProjectScope {
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        requireText(projectName, "projectName");
        requireText(teamId, "teamId");
    }

    public String projectIdValue() {
        return projectId.toString();
    }

    public AuthorizationService.Scope authorizationScope() {
        return new AuthorizationService.Scope(tenantId, projectIdValue(), teamId);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
