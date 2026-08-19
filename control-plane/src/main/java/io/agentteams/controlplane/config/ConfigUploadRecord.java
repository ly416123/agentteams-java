package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConfigUploadRecord(UUID id, UUID snapshotId, String path, String storageKey,
        String contentType, String expectedChecksum, long expectedSizeBytes, String status,
        Instant createdAt, Instant expiresAt, Instant completedAt, Instant deletedAt) {
    public ConfigUploadRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshotId, "snapshotId");
        requireText(path, "path");
        requireText(storageKey, "storageKey");
        requireText(contentType, "contentType");
        requireText(expectedChecksum, "expectedChecksum");
        if (expectedSizeBytes < 0) throw new IllegalArgumentException("expectedSizeBytes must not be negative");
        if (!("PENDING".equals(status) || "COMPLETED".equals(status) || "DELETED".equals(status))) {
            throw new IllegalArgumentException("unsupported config upload status");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean pending() { return "PENDING".equals(status); }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
