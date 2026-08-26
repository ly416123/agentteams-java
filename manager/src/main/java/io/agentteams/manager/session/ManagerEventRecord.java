package io.agentteams.manager.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ManagerEventRecord(UUID sessionId, long cursor, String type, String payload, Instant createdAt) {
    public ManagerEventRecord {
        Objects.requireNonNull(sessionId, "sessionId");
        if (cursor <= 0) throw new IllegalArgumentException("cursor must be positive");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("payload must not be blank");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
