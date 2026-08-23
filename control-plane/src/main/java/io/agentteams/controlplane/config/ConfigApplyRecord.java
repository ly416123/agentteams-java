package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConfigApplyRecord(UUID id, UUID bindingId, UUID agentId, UUID snapshotId, String phase,
        String errorMessage, Instant appliedAt, Instant updatedAt, Long observedVersion, String failureCode,
        boolean rollback) {
    public ConfigApplyRecord(UUID id, UUID bindingId, UUID agentId, UUID snapshotId, String phase,
            String errorMessage, Instant appliedAt, Instant updatedAt) {
        this(id, bindingId, agentId, snapshotId, phase, errorMessage, appliedAt, updatedAt, null,
                ConfigFailureClassifier.classify(errorMessage), false);
    }

    public ConfigApplyRecord(UUID id, UUID bindingId, UUID agentId, UUID snapshotId, String phase,
            String errorMessage, Instant appliedAt, Instant updatedAt, Long observedVersion, String failureCode) {
        this(id, bindingId, agentId, snapshotId, phase, errorMessage, appliedAt, updatedAt, observedVersion,
                failureCode, false);
    }

    public ConfigApplyRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        if (phase == null || phase.isBlank()) throw new IllegalArgumentException("phase must not be blank");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (observedVersion != null && observedVersion <= 0) {
            throw new IllegalArgumentException("observedVersion must be positive");
        }
    }
}
