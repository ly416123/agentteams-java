package io.agentteams.controlplane.persistence;

import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskRecord(
        UUID id,
        String title,
        String description,
        TaskPhase phase,
        int priority,
        String specJson,
        String actor,
        String source,
        String failureCode,
        String redactedFailureMessage,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskRecord {
        Objects.requireNonNull(id, "id");
        requireText(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(specJson, "specJson");
        requireText(actor, "actor");
        requireText(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static TaskRecord draft(UUID id, String title, String description,
            String actor, String source, Instant now) {
        return new TaskRecord(id, title, description, TaskPhase.DRAFT, 0, "{}", actor, source,
                null, null, now, now, 0);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
