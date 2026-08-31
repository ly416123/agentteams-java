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
        String reviewStatus,
        String packageStorageKey,
        Long packageSizeBytes,
        String packageSha256,
        String packageUploadStatus,
        String organizationId,
        String tenantId) {

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
        requireText(packageUploadStatus, "packageUploadStatus");
        if (!packageUploadStatus.equals("NOT_STARTED") && !packageUploadStatus.equals("PENDING")
                && !packageUploadStatus.equals("COMPLETED")) {
            throw new IllegalArgumentException("packageUploadStatus is invalid");
        }
        if (packageSizeBytes != null && packageSizeBytes < 0) {
            throw new IllegalArgumentException("packageSizeBytes must not be negative");
        }
        if (packageStorageKey == null && (packageSizeBytes != null || packageSha256 != null)) {
            throw new IllegalArgumentException("package metadata is incomplete");
        }
        if (packageStorageKey != null && (packageSizeBytes == null || packageSha256 == null)) {
            throw new IllegalArgumentException("package metadata is incomplete");
        }
        if (packageUploadStatus.equals("NOT_STARTED") && packageStorageKey != null) {
            throw new IllegalArgumentException("NOT_STARTED package cannot have storage metadata");
        }
        if (packageUploadStatus.equals("COMPLETED") && packageStorageKey == null) {
            throw new IllegalArgumentException("COMPLETED package must have storage metadata");
        }
        if ((organizationId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("organizationId and tenantId must be supplied together");
        }
    }

    public SkillVersionRecord(UUID id, UUID skillId, String version, String digest, String manifestJson,
            String visibility, String lifecycle, Instant createdAt, Instant updatedAt, long recordVersion) {
        this(id, skillId, version, digest, manifestJson, visibility, lifecycle, createdAt, updatedAt, recordVersion,
                "PUBLISHED".equals(lifecycle) ? "PASSED" : "NOT_SCANNED",
                "PUBLISHED".equals(lifecycle) ? "APPROVED" : "PENDING",
                null, null, null, "NOT_STARTED", null, null);
    }

    /** Compatibility constructor for callers that already provide scan/review state. */
    public SkillVersionRecord(UUID id, UUID skillId, String version, String digest, String manifestJson,
            String visibility, String lifecycle, Instant createdAt, Instant updatedAt, long recordVersion,
            String securityScanStatus, String reviewStatus) {
        this(id, skillId, version, digest, manifestJson, visibility, lifecycle, createdAt, updatedAt, recordVersion,
                securityScanStatus, reviewStatus, null, null, null, "NOT_STARTED");
    }

    public SkillVersionRecord(UUID id, UUID skillId, String version, String digest, String manifestJson,
            String visibility, String lifecycle, Instant createdAt, Instant updatedAt, long recordVersion,
            String securityScanStatus, String reviewStatus, String packageStorageKey, Long packageSizeBytes,
            String packageSha256, String packageUploadStatus) {
        this(id, skillId, version, digest, manifestJson, visibility, lifecycle, createdAt, updatedAt, recordVersion,
                securityScanStatus, reviewStatus, packageStorageKey, packageSizeBytes, packageSha256,
                packageUploadStatus, null, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
