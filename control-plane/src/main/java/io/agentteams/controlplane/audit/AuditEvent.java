package io.agentteams.controlplane.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(UUID id, String actor, String action, String resourceType, String resourceId,
        Map<String, String> attributes, Instant occurredAt) {
    public AuditEvent {
        Objects.requireNonNull(id, "id");
        requireText(actor, "actor");
        requireText(action, "action");
        requireText(resourceType, "resourceType");
        requireText(resourceId, "resourceId");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
