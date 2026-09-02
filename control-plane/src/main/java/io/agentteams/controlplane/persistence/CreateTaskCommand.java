package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.Objects;

public record CreateTaskCommand(
        String idempotencyKey,
        String title,
        String description,
        String actor,
        String source,
        String specJson,
        Instant createdAt,
        String taskType) {

    public CreateTaskCommand(String idempotencyKey, String title, String description,
            String actor, String source, String specJson, Instant createdAt) {
        this(idempotencyKey, title, description, actor, source, specJson, createdAt, "NORMAL");
    }

    public CreateTaskCommand {
        requireText(idempotencyKey, "idempotencyKey");
        requireText(title, "title");
        Objects.requireNonNull(description, "description");
        requireText(actor, "actor");
        requireText(source, "source");
        Objects.requireNonNull(specJson, "specJson");
        Objects.requireNonNull(createdAt, "createdAt");
        if (taskType == null || taskType.isBlank()) throw new IllegalArgumentException("taskType must not be blank");
        taskType = taskType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static CreateTaskCommand of(String idempotencyKey, String title, String description,
            String actor, String source, Instant createdAt) {
        return new CreateTaskCommand(idempotencyKey, title, description, actor, source, "{}", createdAt);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
