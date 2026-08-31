package io.agentteams.controlplane.skill;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable version-pinned Skill binding for one organization resource scope. */
public record SkillBindingRecord(UUID id, String organizationId, String tenantId, String projectId, String teamId,
        UUID skillId, UUID skillVersionId, String digest, Instant createdAt, String createdBy) {
    public SkillBindingRecord {
        Objects.requireNonNull(id, "id");
        required(organizationId, "organizationId");
        required(tenantId, "tenantId");
        required(projectId, "projectId");
        required(teamId, "teamId");
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(skillVersionId, "skillVersionId");
        required(digest, "digest");
        Objects.requireNonNull(createdAt, "createdAt");
        required(createdBy, "createdBy");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
