package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdempotencyKeyRecord(
        UUID id,
        String idempotencyKey,
        String operation,
        String requestHash,
        String resourceType,
        UUID resourceId,
        String responsePayloadJson,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public IdempotencyKeyRecord {
        Objects.requireNonNull(id, "id");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(operation, "operation");
        requireText(requestHash, "requestHash");
        requireText(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(responsePayloadJson, "responsePayloadJson");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
