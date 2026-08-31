package io.agentteams.controlplane.webhook;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WebhookDelivery(UUID id, UUID subscriptionId, UUID eventId, String endpoint, String secretRef,
        String payloadJson, Status status, int attempts, Instant nextAttemptAt, Instant createdAt,
        Instant updatedAt, String lastError) {
    public enum Status { PENDING, SENT, DEAD }

    public WebhookDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(secretRef, "secretRef");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(status, "status");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
