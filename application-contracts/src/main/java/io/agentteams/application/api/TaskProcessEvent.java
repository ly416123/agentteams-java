package io.agentteams.application.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Re-playable, public-facing fact about task execution progress. */
public record TaskProcessEvent(UUID eventId, UUID taskId, UUID runId, long sequence,
        String eventType, TaskEventVisibility visibility, Instant occurredAt,
        String correlationId, String payload, String payloadRef) {

    private static final int MAX_INLINE_PAYLOAD_BYTES = 16 * 1024;

    public TaskProcessEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(occurredAt, "occurredAt");
        eventType = requireText(eventType, "eventType");
        correlationId = requireText(correlationId, "correlationId");
        payload = optionalPayload(payload);
        payloadRef = optionalText(payloadRef);
        TaskProcessPayloadPolicy.requireSafe(visibility, eventType, payload);
        if ((payload == null) == (payloadRef == null)) {
            throw new IllegalArgumentException("exactly one of payload or payloadRef is required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds the inline size limit");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String optionalPayload(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
