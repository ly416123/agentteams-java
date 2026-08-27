package io.agentteams.application.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for durable runtime configuration acknowledgements. */
public interface ConfigEventPort {

    void applied(ConfigAppliedCommand command);

    record ConfigAppliedCommand(UUID eventId, UUID bindingId, UUID snapshotId, UUID agentId,
            long configVersion, boolean applied, String errorMessage, Instant occurredAt,
            String source, String correlationId, List<ResourceApplyResult> resourceResults) {
        public ConfigAppliedCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(source, "source");
            correlationId = correlationId == null || correlationId.isBlank() ? "unknown" : correlationId;
            if (configVersion <= 0) throw new IllegalArgumentException("configVersion must be positive");
            if (correlationId.length() > 128 || !correlationId.matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalArgumentException("correlationId contains unsupported characters");
            }
            errorMessage = errorMessage == null ? "" : errorMessage;
            resourceResults = List.copyOf(Objects.requireNonNull(resourceResults, "resourceResults"));
        }

        public ConfigAppliedCommand(UUID eventId, UUID bindingId, UUID snapshotId, UUID agentId,
                long configVersion, boolean applied, String errorMessage, Instant occurredAt,
                String source, String correlationId) {
            this(eventId, bindingId, snapshotId, agentId, configVersion, applied, errorMessage,
                    occurredAt, source, correlationId, List.of());
        }

        public ConfigAppliedCommand(UUID eventId, UUID bindingId, UUID snapshotId, UUID agentId,
                long configVersion, boolean applied, String errorMessage, Instant occurredAt, String source) {
            this(eventId, bindingId, snapshotId, agentId, configVersion, applied, errorMessage,
                    occurredAt, source, "unknown");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    record ResourceApplyResult(String type, String resourceId, String revision,
            String expectedDigest, String observedDigest, String status, String failureCategory) {
        private static final List<String> STATUSES = List.of("APPLIED", "REJECTED", "FAILED");
        private static final List<String> FAILURE_CATEGORIES = List.of("", "NOT_VISIBLE", "NOT_PUBLISHED",
                "DIGEST_MISMATCH", "DOWNLOAD_FAILED", "AUTH_UNAVAILABLE", "POLICY_REJECTED",
                "RUNTIME_UNSUPPORTED");

        public ResourceApplyResult {
            requireText(type, "type");
            requireText(resourceId, "resourceId");
            requireText(revision, "revision");
            requireText(expectedDigest, "expectedDigest");
            observedDigest = observedDigest == null ? "" : observedDigest;
            requireText(status, "status");
            if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported resource apply status");
            failureCategory = failureCategory == null ? "" : failureCategory;
            if (!FAILURE_CATEGORIES.contains(failureCategory)) {
                throw new IllegalArgumentException("unsupported resource failure category");
            }
            if ("APPLIED".equals(status) && !failureCategory.isEmpty()) {
                throw new IllegalArgumentException("applied resource result cannot contain failure category");
            }
            if (!"APPLIED".equals(status) && failureCategory.isEmpty()) {
                throw new IllegalArgumentException("failed resource result requires failure category");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
