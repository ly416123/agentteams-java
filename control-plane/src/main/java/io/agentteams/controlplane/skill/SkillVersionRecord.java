package io.agentteams.controlplane.skill;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SkillVersionRecord(
        UUID id,
        UUID skillId,
        String version,
        String digest,
        String manifestJson,
        String visibility,
        String lifecycle,
        Instant createdAt,
        Instant updatedAt,
        long recordVersion,
        String securityScanStatus,
        String reviewStatus) {

    public SkillVersionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(skillId, "skillId");
        requireText(version, "version");
        requireText(digest, "digest");
        Objects.requireNonNull(manifestJson, "manifestJson");
        requireText(visibility, "visibility");
        requireText(lifecycle, "lifecycle");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (recordVersion < 0) {
            throw new IllegalArgumentException("recordVersion must not be negative");
        }
        requireText(securityScanStatus, "securityScanStatus");
        requireText(reviewStatus, "reviewStatus");
    }

    public SkillVersionRecord(UUID id, UUID skillId, String version, String digest, String manifestJson,
            String visibility, String lifecycle, Instant createdAt, Instant updatedAt, long recordVersion) {
        this(id, skillId, version, digest, manifestJson, visibility, lifecycle, createdAt, updatedAt, recordVersion,
                "PUBLISHED".equals(lifecycle) ? "PASSED" : "NOT_SCANNED",
                "PUBLISHED".equals(lifecycle) ? "APPROVED" : "PENDING");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
