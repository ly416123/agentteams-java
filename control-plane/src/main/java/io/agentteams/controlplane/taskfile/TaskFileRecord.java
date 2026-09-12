package io.agentteams.controlplane.taskfile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of the G05 task-file ledger; subtasks are task rows (G03) so they are covered. */
public record TaskFileRecord(UUID id, UUID taskId, UUID attemptId, String role, String name,
        String contentType, long sizeBytes, String sha256, String storageKey,
        UUID sourceSessionId, UUID sourceFileId, String status, Instant createdAt, Instant updatedAt) {

    public static final String INPUT = "INPUT";
    public static final String OUTPUT = "OUTPUT";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String MISSING = "MISSING";

    public TaskFileRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        if (!INPUT.equals(role) && !OUTPUT.equals(role)) {
            throw new IllegalArgumentException("role must be INPUT or OUTPUT");
        }
        Objects.requireNonNull(name, "name");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean isInput() {
        return INPUT.equals(role);
    }
}
