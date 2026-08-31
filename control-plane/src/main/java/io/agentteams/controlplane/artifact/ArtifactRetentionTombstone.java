package io.agentteams.controlplane.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable deletion intent and retry state; the source artifact row remains auditable. */
public record ArtifactRetentionTombstone(UUID id, UUID artifactId, UUID taskId, String storageKey,
        String status, boolean legalHold, int attempts, Instant nextAttemptAt, String operator, long policyVersion) {
    public ArtifactRetentionTombstone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(taskId, "taskId");
        if (storageKey == null || storageKey.isBlank()) throw new IllegalArgumentException("storageKey must not be blank");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("operator must not be blank");
        if (policyVersion < 0) throw new IllegalArgumentException("policyVersion must not be negative");
    }
}
