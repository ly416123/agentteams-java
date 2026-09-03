package io.agentteams.controlplane.template;

import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkerTemplateRevision(UUID templateId, long revision, String specJson, String digest,
        WorkerType workerType, TemplateStatus status, String createdBy, Instant createdAt, Instant updatedAt,
        long version) {
    public WorkerTemplateRevision(UUID templateId, long revision, String specJson, String digest,
            TemplateStatus status, String createdBy, Instant createdAt, Instant updatedAt, long version) {
        this(templateId, revision, specJson, digest, WorkerType.EXECUTOR, status, createdBy, createdAt, updatedAt,
                version);
    }

    public WorkerTemplateRevision {
        Objects.requireNonNull(templateId, "templateId");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        requireText(specJson, "specJson"); requireText(digest, "digest"); requireText(createdBy, "createdBy");
        Objects.requireNonNull(workerType, "workerType");
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
