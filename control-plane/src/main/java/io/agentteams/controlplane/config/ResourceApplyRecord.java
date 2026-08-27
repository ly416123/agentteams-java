package io.agentteams.controlplane.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable result for one immutable resource binding in a configuration revision. */
public record ResourceApplyRecord(UUID bindingId, UUID snapshotId, UUID agentId, long configVersion,
        String resourceType, String resourceId, String revision, String expectedDigest,
        String observedDigest, String status, String failureCategory, Instant observedAt) {
    public ResourceApplyRecord {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(agentId, "agentId");
        if (configVersion <= 0) throw new IllegalArgumentException("configVersion must be positive");
        requireText(resourceType, "resourceType");
        requireText(resourceId, "resourceId");
        requireText(revision, "revision");
        requireText(expectedDigest, "expectedDigest");
        observedDigest = observedDigest == null ? "" : observedDigest;
        if (!ListSupport.STATUSES.contains(status)) throw new IllegalArgumentException("unsupported resource apply status");
        failureCategory = failureCategory == null ? "" : failureCategory;
        if (!ListSupport.FAILURE_CATEGORIES.contains(failureCategory)) {
            throw new IllegalArgumentException("unsupported resource failure category");
        }
        if ("APPLIED".equals(status) && !failureCategory.isEmpty()) {
            throw new IllegalArgumentException("applied resource result cannot contain failure category");
        }
        if (!"APPLIED".equals(status) && failureCategory.isEmpty()) {
            throw new IllegalArgumentException("failed resource result requires failure category");
        }
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static final class ListSupport {
        private static final java.util.List<String> STATUSES = java.util.List.of("APPLIED", "REJECTED", "FAILED");
        private static final java.util.List<String> FAILURE_CATEGORIES = java.util.List.of("", "NOT_VISIBLE",
                "NOT_PUBLISHED", "DIGEST_MISMATCH", "DOWNLOAD_FAILED", "AUTH_UNAVAILABLE",
                "POLICY_REJECTED", "RUNTIME_UNSUPPORTED");
    }
}
