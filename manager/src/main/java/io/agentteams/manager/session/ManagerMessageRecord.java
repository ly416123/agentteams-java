package io.agentteams.manager.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagerMessageRecord(UUID id, UUID sessionId, String idempotencyKey, String actor,
        String role, String contentHash, String redactedSummary, String resultSummary, Instant createdAt) {
    public ManagerMessageRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(actor, "actor");
        requireText(role, "role");
        requireText(contentHash, "contentHash");
        requireText(redactedSummary, "redactedSummary");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ManagerMessageRecord completed(UUID id, UUID sessionId, String key, String actor,
            String contentHash, String resultSummary, Instant createdAt) {
        return new ManagerMessageRecord(id, sessionId, key, actor, "user", contentHash,
                "message accepted", resultSummary, createdAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
