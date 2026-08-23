package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ModelRecord(
        UUID id,
        UUID providerId,
        String name,
        String modelId,
        String capabilitiesJson,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public ModelRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerId, "providerId");
        requireText(name, "name");
        requireText(modelId, "modelId");
        Objects.requireNonNull(capabilitiesJson, "capabilitiesJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
