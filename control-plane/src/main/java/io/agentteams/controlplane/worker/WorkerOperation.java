package io.agentteams.controlplane.worker;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerOperation(
        UUID id,
        UUID agentId,
        WorkerOperationType type,
        WorkerOperationStatus status,
        String requestedSpecDigest,
        String requestedRuntime,
        String requestedConfigRevision,
        String requestedSecretGeneration,
        String previousStableSpec,
        String idempotencyKey,
        long expectedAgentVersion,
        String owner,
        Instant leaseExpiresAt,
        String failureCategory,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public WorkerOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        requireText(previousStableSpec, "previousStableSpec");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(correlationId, "correlationId");
        if (type == WorkerOperationType.ROLLOUT) {
            requireText(requestedSpecDigest, "requestedSpecDigest");
            requireText(requestedRuntime, "requestedRuntime");
            requireText(requestedConfigRevision, "requestedConfigRevision");
            requireText(requestedSecretGeneration, "requestedSecretGeneration");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (expectedAgentVersion < 0 || version < 0) {
            throw new IllegalArgumentException("operation versions must not be negative");
        }
    }

    public static WorkerOperation pending(UUID id, UUID agentId, WorkerOperationType type,
            String requestedSpecDigest, String previousStableSpec, String idempotencyKey,
            long expectedAgentVersion, String owner, Instant leaseExpiresAt, String correlationId, Instant now) {
        return pending(id, agentId, type, requestedSpecDigest, null, null, null, previousStableSpec,
                idempotencyKey, expectedAgentVersion, owner, leaseExpiresAt, correlationId, now);
    }

    public static WorkerOperation pending(UUID id, UUID agentId, WorkerOperationType type,
            String requestedSpecDigest, String requestedRuntime, String requestedConfigRevision,
            String requestedSecretGeneration, String previousStableSpec, String idempotencyKey,
            long expectedAgentVersion, String owner, Instant leaseExpiresAt, String correlationId, Instant now) {
        return new WorkerOperation(id, agentId, type, WorkerOperationStatus.PENDING,
                requestedSpecDigest, requestedRuntime, requestedConfigRevision, requestedSecretGeneration,
                previousStableSpec, idempotencyKey, expectedAgentVersion, owner, leaseExpiresAt, null,
                correlationId, now, now, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
