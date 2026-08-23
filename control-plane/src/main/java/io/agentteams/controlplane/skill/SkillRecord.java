package io.agentteams.controlplane.skill;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SkillRecord(
        UUID id,
        String name,
        String displayName,
        String description,
        String visibility,
        String lifecycle,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public SkillRecord {
        Objects.requireNonNull(id, "id");
        requireText(name, "name");
        requireText(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        requireText(visibility, "visibility");
        requireText(lifecycle, "lifecycle");
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
