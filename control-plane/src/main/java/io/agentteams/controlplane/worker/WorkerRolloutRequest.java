package io.agentteams.controlplane.worker;

import java.util.Objects;
import java.util.UUID;

public record WorkerRolloutRequest(
        long expectedAgentVersion,
        String idempotencyKey,
        String imageDigest,
        String runtime,
        String configRevision,
        String secretGeneration,
        String previousStableSpec,
        String owner,
        String correlationId) {

    public WorkerRolloutRequest {
        if (expectedAgentVersion < 0) {
            throw new IllegalArgumentException("expectedAgentVersion must not be negative");
        }
        requireText(idempotencyKey, "idempotencyKey");
        requireText(imageDigest, "imageDigest");
        requireText(runtime, "runtime");
        requireText(configRevision, "configRevision");
        requireText(secretGeneration, "secretGeneration");
        requireText(previousStableSpec, "previousStableSpec");
        requireText(owner, "owner");
        requireText(correlationId, "correlationId");
    }

    public WorkerRolloutRequest(long expectedAgentVersion, String idempotencyKey, String imageDigest,
            String runtime, String configRevision, String secretGeneration) {
        this(expectedAgentVersion, idempotencyKey, imageDigest, runtime, configRevision, secretGeneration,
                "{}", "control-plane", UUID.randomUUID().toString());
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
