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
        long version,
        String taskType) {

    public TaskRecord(UUID id, String title, String description, TaskPhase phase, int priority,
            String specJson, String actor, String source, String failureCode,
            String redactedFailureMessage, Instant createdAt, Instant updatedAt, long version) {
        this(id, title, description, phase, priority, specJson, actor, source, failureCode,
                redactedFailureMessage, createdAt, updatedAt, version, "NORMAL");
    }

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
        taskType = requireType(taskType);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    private static String requireType(String value) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("taskType must be a non-blank identifier");
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
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
