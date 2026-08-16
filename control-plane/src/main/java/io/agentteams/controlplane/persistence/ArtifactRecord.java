package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ArtifactRecord(
        UUID id,
        UUID taskId,
        UUID attemptId,
        String name,
        String storageKey,
        String contentType,
        long sizeBytes,
        String sha256,
        String status,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public ArtifactRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        requireText(name, "name");
        requireText(storageKey, "storageKey");
        requireText(contentType, "contentType");
        requireText(sha256, "sha256");
        requireText(status, "status");
        Objects.requireNonNull(metadataJson, "metadataJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (sizeBytes < 0 || version < 0) {
            throw new IllegalArgumentException("sizeBytes and version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
