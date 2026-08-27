package io.agentteams.controlplane.security;

import java.util.Objects;
import java.util.UUID;

/** Explicit resource scope passed to the authorization matrix. */
public record ResourceRef(String tenantId, UUID projectId, String teamId) {
    public ResourceRef {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        Objects.requireNonNull(projectId, "projectId");
        if (teamId != null && teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
        tenantId = tenantId.trim();
        teamId = teamId == null ? null : teamId.trim();
    }

    public static ResourceRef project(String tenantId, UUID projectId) {
        return new ResourceRef(tenantId, projectId, null);
    }

    public static ResourceRef team(String tenantId, UUID projectId, String teamId) {
        return new ResourceRef(tenantId, projectId, teamId);
    }
}
