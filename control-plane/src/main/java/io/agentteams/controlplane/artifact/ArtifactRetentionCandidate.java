package io.agentteams.controlplane.artifact;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Artifact selected for durable tombstoning before object deletion. */
public record ArtifactRetentionCandidate(UUID artifactId, UUID taskId, String storageKey,
        Instant createdAt, ArtifactRetentionPolicy policy, long policyVersion, String policySource) {
    public ArtifactRetentionCandidate {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(taskId, "taskId");
        requireText(storageKey, "storageKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(policy, "policy");
        if (policyVersion < 0) throw new IllegalArgumentException("policyVersion must not be negative");
        requireText(policySource, "policySource");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
