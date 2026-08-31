package io.agentteams.controlplane.webhook;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record WebhookSubscription(UUID id, WebhookScope scope, String endpoint, String secretRef,
        Set<String> eventTypes, boolean enabled, long version, Instant createdAt, Instant updatedAt) {
    public WebhookSubscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        endpoint = required(endpoint, "endpoint");
        secretRef = required(secretRef, "secretRef");
        eventTypes = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes"));
        if (eventTypes.isEmpty()) throw new IllegalArgumentException("eventTypes must not be empty");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
