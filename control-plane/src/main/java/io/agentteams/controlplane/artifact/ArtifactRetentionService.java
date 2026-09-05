package io.agentteams.controlplane.artifact;

import io.agentteams.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies retention policy in two durable phases: record a tombstone, then
 * delete bytes from object storage. A failed object deletion remains retryable.
 */
public final class ArtifactRetentionService {
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final ArtifactRetentionRepository repository;
    private final ObjectStorage storage;
    private final Clock clock;
    private final String operator;

    public ArtifactRetentionService(ArtifactRetentionRepository repository, ObjectStorage storage, Clock clock) {
        this(repository, storage, clock, "system:artifact-retention");
    }

    public ArtifactRetentionService(ArtifactRetentionRepository repository, ObjectStorage storage, Clock clock,
            String operator) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.operator = requireText(operator, "operator");
    }

    public CleanupResult cleanup(ArtifactRetentionPolicy fallback, int batchSize) {
        Objects.requireNonNull(fallback, "fallback");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        Instant now = clock.instant();
        int tombstoned = 0;
        int held = 0;
        for (ArtifactRetentionCandidate candidate : repository.findExpiredCandidates(now, fallback, batchSize)) {
            String status = candidate.policy().legalHold() ? "HELD" : "PENDING";
            if (repository.insertTombstone(candidate, sha256(candidate.storageKey()), now, status,
                    policyJson(candidate.policy(), candidate.policyVersion(), candidate.policySource()), operator)) {
                tombstoned++;
                if (candidate.policy().legalHold()) held++;
            }
        }

        int deleted = 0;
        int failed = 0;
        for (ArtifactRetentionTombstone tombstone : repository.findDueTombstones(now, batchSize)) {
            if ("HELD".equals(tombstone.status()) || "DELETED".equals(tombstone.status())) continue;
            try {
                storage.delete(tombstone.storageKey());
                repository.markDeleted(tombstone.id(), tombstone.artifactId(), now);
                deleted++;
            } catch (RuntimeException error) {
                repository.markFailed(tombstone.id(), now, now.plus(retryDelay(tombstone.attempts())),
                        safeError(error));
                failed++;
            }
        }
        return new CleanupResult(tombstoned, held, deleted, failed);
    }

    public void upsertProjectPolicy(String tenantId, String projectId, ArtifactRetentionPolicy policy, Instant now) {
        repository.upsertProjectPolicy(requireText(tenantId, "tenantId"), requireText(projectId, "projectId"),
                Objects.requireNonNull(policy, "policy"), Objects.requireNonNull(now, "now"));
    }

    public void upsertTaskOverride(UUID taskId, ArtifactRetentionPolicy policy, Instant now) {
        repository.upsertTaskOverride(Objects.requireNonNull(taskId, "taskId"), Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(now, "now"));
    }

    private static Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts, 0), 6);
        Duration delay = INITIAL_RETRY_DELAY.multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private static String policyJson(ArtifactRetentionPolicy policy, long version, String source) {
        return "{\"version\":" + version + ",\"successfulTaskRetentionSeconds\":"
                + policy.successfulTaskRetentionSeconds()
                + ",\"failedTaskRetentionSeconds\":" + policy.failedTaskRetentionSeconds()
                + ",\"temporaryUploadRetentionSeconds\":" + policy.temporaryUploadRetentionSeconds()
                + ",\"legalHold\":" + policy.legalHold() + ",\"source\":\""
                + source.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String safeError(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(message.length(), 1000));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record CleanupResult(int tombstoned, int held, int deleted, int failed) {
        public CleanupResult {
            if (tombstoned < 0 || held < 0 || deleted < 0 || failed < 0) {
                throw new IllegalArgumentException("cleanup counts must not be negative");
            }
        }
    }
}
