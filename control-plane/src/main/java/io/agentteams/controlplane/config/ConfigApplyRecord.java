package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConfigApplyRecord(UUID id, UUID bindingId, UUID agentId, UUID snapshotId, String phase,
        String errorMessage, Instant appliedAt, Instant updatedAt) {
    public ConfigApplyRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (phase == null || phase.isBlank()) throw new IllegalArgumentException("phase must not be blank");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
