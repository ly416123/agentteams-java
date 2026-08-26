package io.agentteams.controlplane.config;

import java.util.Objects;
import java.util.UUID;

public record EffectiveConfigRequest(UUID agentId, UUID teamId, long teamRevision, UUID taskId,
        String baseManifest, String teamOverlay, String taskOverlay) {
    public EffectiveConfigRequest {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(teamId, "teamId");
        if (teamRevision < 1) throw new IllegalArgumentException("teamRevision must be positive");
        requireJsonText(baseManifest, "baseManifest");
        requireJsonText(teamOverlay, "teamOverlay");
        requireJsonText(taskOverlay, "taskOverlay");
    }

    private static void requireJsonText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
