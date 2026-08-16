package io.agentteams.controlplane.config;

import java.util.Objects;
import java.util.UUID;

public record ConfigFileRecord(UUID id, UUID snapshotId, String path, String storageKey,
        String checksum, long sizeBytes, String contentType) {
    public ConfigFileRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshotId, "snapshotId");
        requireText(path, "path");
        requireText(storageKey, "storageKey");
        requireText(checksum, "checksum");
        requireText(contentType, "contentType");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
