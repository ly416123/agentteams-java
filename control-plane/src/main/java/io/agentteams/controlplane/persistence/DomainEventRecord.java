package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DomainEventRecord(
        UUID id,
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payloadJson,
        Instant occurredAt,
        long aggregateVersion,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public DomainEventRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(eventId, "eventId");
        requireText(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (aggregateVersion < 0 || version < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }

    public static DomainEventRecord create(UUID eventId, String aggregateType, UUID aggregateId,
            String eventType, String payloadJson, Instant occurredAt, long aggregateVersion) {
        return new DomainEventRecord(UUID.randomUUID(), eventId, aggregateType, aggregateId, eventType,
                payloadJson, occurredAt, aggregateVersion, occurredAt, occurredAt, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
