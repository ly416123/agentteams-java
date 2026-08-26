package io.agentteams.controlplane.config;

import java.util.Objects;
import java.util.UUID;

/** Non-secret provenance for an immutable effective configuration snapshot. */
public record ConfigProvenance(UUID agentId, UUID teamId, long teamRevision, UUID taskId,
        String schemaVersion) {
    public ConfigProvenance {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(teamId, "teamId");
        if (teamRevision < 1) throw new IllegalArgumentException("teamRevision must be positive");
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
    }

    public ConfigProvenance(UUID agentId, UUID teamId, long teamRevision, UUID taskId) {
        this(agentId, teamId, teamRevision, taskId, "v1");
    }
}
