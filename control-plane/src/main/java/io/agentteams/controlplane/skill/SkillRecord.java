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
        long version,
        String organizationId,
        String tenantId) {

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
        if ((organizationId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("organizationId and tenantId must be supplied together");
        }
    }

    public SkillRecord(UUID id, String name, String displayName, String description, String visibility,
            String lifecycle, Instant createdAt, Instant updatedAt, long version) {
        this(id, name, displayName, description, visibility, lifecycle, createdAt, updatedAt, version, null, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
