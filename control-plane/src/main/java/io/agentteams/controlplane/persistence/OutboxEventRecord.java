package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxEventRecord(
        UUID id,
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        long aggregateVersion,
        Instant occurredAt,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        UUID claimToken,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String correlationId,
        String traceparent,
        String tracestate) {

    public OutboxEventRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        requireText(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (aggregateVersion < 0 || attempts < 0 || version < 0) {
            throw new IllegalArgumentException("aggregateVersion, attempts, and version must not be negative");
        }
        correlationId = correlationId == null || correlationId.isBlank() ? "unknown" : correlationId;
        traceparent = traceparent == null ? "" : traceparent;
        tracestate = tracestate == null ? "" : tracestate;
    }

    public OutboxEventRecord(UUID id, UUID eventId, String aggregateType, UUID aggregateId, String eventType,
            String payloadJson, long aggregateVersion, Instant occurredAt, String status, int attempts,
            Instant nextAttemptAt, String lastError, UUID claimToken, Instant createdAt, Instant updatedAt,
            long version) {
        this(id, eventId, aggregateType, aggregateId, eventType, payloadJson, aggregateVersion, occurredAt, status,
                attempts, nextAttemptAt, lastError, claimToken, createdAt, updatedAt, version, "unknown", "", "");
    }

    public static OutboxEventRecord pending(UUID eventId, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson, Instant now) {
        return pending(eventId, aggregateType, aggregateId, eventType, payloadJson, 0, now, now);
    }

    public static OutboxEventRecord pending(UUID eventId, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson, long aggregateVersion, Instant occurredAt, Instant now) {
        return new OutboxEventRecord(UUID.randomUUID(), eventId, aggregateType, aggregateId, eventType,
                payloadJson, aggregateVersion, occurredAt, "PENDING", 0, now, null, null, now, now, 0,
                "unknown", "", "");
    }

    public static OutboxEventRecord pending(UUID eventId, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson, long aggregateVersion, Instant occurredAt, Instant now,
            io.agentteams.application.api.TraceContext context) {
        io.agentteams.application.api.TraceContext safe = context == null
                ? io.agentteams.application.api.TraceContext.empty() : context;
        return new OutboxEventRecord(UUID.randomUUID(), eventId, aggregateType, aggregateId, eventType,
                payloadJson, aggregateVersion, occurredAt, "PENDING", 0, now, null, null, now, now, 0,
                safe.correlationId(), safe.traceparent(), safe.tracestate());
    }

    public OutboxEventRecord withAttempts(int newAttempts) {
        return new OutboxEventRecord(id, eventId, aggregateType, aggregateId, eventType, payloadJson,
                aggregateVersion, occurredAt, status, newAttempts, nextAttemptAt, lastError, claimToken,
                createdAt, updatedAt, version, correlationId, traceparent, tracestate);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
