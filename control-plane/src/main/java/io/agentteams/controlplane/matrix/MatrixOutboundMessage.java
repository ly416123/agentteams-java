package io.agentteams.controlplane.matrix;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MatrixOutboundMessage(UUID id, String roomId, String eventType, String body,
        String status, int attempts, Instant nextAttemptAt, String lastError) {
    public MatrixOutboundMessage {
        Objects.requireNonNull(id, "id");
        requireText(roomId, "roomId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(body, "body");
        requireText(status, "status");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
