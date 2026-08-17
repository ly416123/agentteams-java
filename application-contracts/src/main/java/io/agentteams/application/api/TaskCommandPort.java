package io.agentteams.application.api;

import java.util.Objects;
import java.util.UUID;

/** Application boundary for creating tasks without exposing persistence models. */
public interface TaskCommandPort {

    TaskCreationResult create(String idempotencyKey, TaskCreateCommand command);

    record TaskCreateCommand(String title, String description, String specJson,
            String actor, String source) {
        public TaskCreateCommand {
            requireText(title, "title");
            description = description == null ? "" : description;
            specJson = specJson == null || specJson.isBlank() ? "{}" : specJson;
            actor = defaultText(actor, "api");
            source = defaultText(source, "api");
        }
    }

    record TaskCreationResult(UUID taskId, String phase, long version) {
        public TaskCreationResult {
            Objects.requireNonNull(taskId, "taskId");
            requireText(phase, "phase");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
