package io.agentteams.manager.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagerToolCallRecord(UUID id, UUID sessionId, String idempotencyKey, String toolName,
        String inputHash, String status, String resultSummary, Instant createdAt) {
    public ManagerToolCallRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(toolName, "toolName");
        requireText(inputHash, "inputHash");
        requireText(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ManagerToolCallRecord completed(UUID id, UUID sessionId, String key, String toolName,
            String inputHash, String resultSummary, Instant createdAt) {
        return new ManagerToolCallRecord(id, sessionId, key, toolName, inputHash, "COMPLETED",
                resultSummary, createdAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
