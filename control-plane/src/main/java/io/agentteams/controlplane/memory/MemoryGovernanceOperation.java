package io.agentteams.controlplane.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemoryGovernanceOperation(UUID id, UUID memoryId, String organizationId, String tenantId,
        String operation, String reason, String actor, String idempotencyKey, Instant createdAt) {
    public MemoryGovernanceOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(memoryId, "memoryId");
        required(organizationId, "organizationId");
        required(tenantId, "tenantId");
        required(operation, "operation");
        required(reason, "reason");
        required(actor, "actor");
        required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
