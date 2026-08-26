package io.agentteams.manager.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagerMessageRecord(UUID id, UUID sessionId, String idempotencyKey, String actor,
        String role, String contentHash, String redactedSummary, String resultSummary, Status status,
        Instant createdAt) {
    public enum Status { PROCESSING, COMPLETED, FAILED }

    /** Compatibility constructor for callers created before durable processing state was added. */
    public ManagerMessageRecord(UUID id, UUID sessionId, String idempotencyKey, String actor,
            String role, String contentHash, String redactedSummary, String resultSummary, Instant createdAt) {
        this(id, sessionId, idempotencyKey, actor, role, contentHash, redactedSummary, resultSummary,
                Status.COMPLETED, createdAt);
    }

    public ManagerMessageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(actor, "actor");
        requireText(role, "role");
        requireText(contentHash, "contentHash");
        requireText(redactedSummary, "redactedSummary");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ManagerMessageRecord processing(UUID id, UUID sessionId, String key, String actor,
            String contentHash, Instant createdAt) {
        return new ManagerMessageRecord(id, sessionId, key, actor, "user", contentHash,
                "message processing", null, Status.PROCESSING, createdAt);
    }

    public static ManagerMessageRecord completed(UUID id, UUID sessionId, String key, String actor,
            String contentHash, String resultSummary, Instant createdAt) {
        return new ManagerMessageRecord(id, sessionId, key, actor, "user", contentHash,
                "message accepted", resultSummary, Status.COMPLETED, createdAt);
    }

    public ManagerMessageRecord completed(String resultSummary) {
        return new ManagerMessageRecord(id, sessionId, idempotencyKey, actor, role, contentHash,
                redactedSummary, resultSummary, Status.COMPLETED, createdAt);
    }

    public ManagerMessageRecord failed(String resultSummary) {
        return new ManagerMessageRecord(id, sessionId, idempotencyKey, actor, role, contentHash,
                redactedSummary, resultSummary, Status.FAILED, createdAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
