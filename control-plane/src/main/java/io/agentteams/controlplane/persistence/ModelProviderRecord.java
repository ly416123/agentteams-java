package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ModelProviderRecord(
        UUID id,
        String name,
        String providerType,
        String endpoint,
        String credentialRef,
        String settingsJson,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public ModelProviderRecord {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        requireText(providerType, "providerType");
        requireText(endpoint, "endpoint");
        Objects.requireNonNull(settingsJson, "settingsJson");
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
