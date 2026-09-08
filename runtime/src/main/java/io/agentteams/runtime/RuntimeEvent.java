package io.agentteams.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A whitelisted middle-of-execution action observed from a runtime stream. */
public record RuntimeEvent(UUID taskId, String eventType, String payloadJson, Instant occurredAt) {
    public RuntimeEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson;
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
