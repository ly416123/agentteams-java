package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConfigSnapshot(UUID id, String subject, long version, String manifestJson, String checksum,
        String actor, Instant createdAt) {
    public ConfigSnapshot {
        Objects.requireNonNull(id, "id");
        requireText(subject, "subject");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        requireText(manifestJson, "manifestJson");
        requireText(checksum, "checksum");
        requireText(actor, "actor");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
