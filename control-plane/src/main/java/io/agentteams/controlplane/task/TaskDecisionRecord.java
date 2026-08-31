package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Governed decision summary; it is not a storage location for chain-of-thought. */
public record TaskDecisionRecord(UUID id, UUID taskId, UUID runId, TaskEventVisibility visibility,
        String goalSummary, String selectedAction, String evidenceSummary, String constraintsSummary,
        Double confidence, Instant createdAt) {
    public TaskDecisionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visibility, "visibility");
        goalSummary = required(goalSummary, "goalSummary");
        selectedAction = required(selectedAction, "selectedAction");
        evidenceSummary = optional(evidenceSummary);
        constraintsSummary = optional(constraintsSummary);
        Objects.requireNonNull(createdAt, "createdAt");
        if (confidence != null && (confidence < 0 || confidence > 1 || confidence.isNaN())) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        rejectSensitive(goalSummary, "goalSummary");
        rejectSensitive(selectedAction, "selectedAction");
        rejectSensitive(evidenceSummary, "evidenceSummary");
        rejectSensitive(constraintsSummary, "constraintsSummary");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) { return value == null ? "" : value.trim(); }

    private static void rejectSensitive(String value, String field) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String marker : new String[] {"token", "password", "secret", "authorization", "system prompt", "chain of thought"}) {
            if (normalized.contains(marker)) throw new IllegalArgumentException(field + " contains restricted material");
        }
    }
}
