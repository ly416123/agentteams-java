package io.agentteams.application.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Serializable envelope used between Gateway and Control Plane adapters. */
public record ExecutionEventEnvelope(int schemaVersion, String type, UUID taskId,
        ExecutionEventPort.TaskExecutionCommand taskExecution,
        ExecutionEventPort.LeaseRenewalCommand leaseRenewal,
        List<ExecutionEventPort.ArtifactReference> artifacts) {

    public ExecutionEventEnvelope {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requireText(type, "type");
        Objects.requireNonNull(taskId, "taskId");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if ("TASK".equals(type) == (taskExecution == null)) {
            throw new IllegalArgumentException("TASK envelope must contain only taskExecution");
        }
        if ("LEASE_RENEWAL".equals(type) == (leaseRenewal == null)) {
            throw new IllegalArgumentException("LEASE_RENEWAL envelope must contain only leaseRenewal");
        }
    }

    public static ExecutionEventEnvelope task(UUID taskId,
            ExecutionEventPort.TaskExecutionCommand command,
            List<ExecutionEventPort.ArtifactReference> artifacts) {
        return new ExecutionEventEnvelope(1, "TASK", taskId, Objects.requireNonNull(command, "command"),
                null, artifacts);
    }

    public static ExecutionEventEnvelope leaseRenewal(UUID taskId,
            ExecutionEventPort.LeaseRenewalCommand command) {
        return new ExecutionEventEnvelope(1, "LEASE_RENEWAL", taskId, null,
                Objects.requireNonNull(command, "command"), List.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
