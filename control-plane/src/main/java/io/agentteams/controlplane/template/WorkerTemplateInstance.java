package io.agentteams.controlplane.template;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerTemplateInstance(UUID id, UUID templateId, long templateRevision, UUID agentSpecId,
        UUID workerId, String status, long currentTemplateRevision, String idempotencyKey, String requestHash,
        Instant createdAt, Instant updatedAt, long version) {
    public WorkerTemplateInstance {
        Objects.requireNonNull(id, "id"); Objects.requireNonNull(templateId, "templateId");
        if (templateRevision < 1 || currentTemplateRevision < 1) throw new IllegalArgumentException("revision must be positive");
        requireText(status, "status"); requireText(idempotencyKey, "idempotencyKey"); requireText(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
