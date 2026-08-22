package io.agentteams.application.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Serializable envelope used between Gateway and Control Plane adapters. */
public record ExecutionEventEnvelope(int schemaVersion, String type, UUID taskId,
        ExecutionEventPort.TaskExecutionCommand taskExecution,
        ExecutionEventPort.LeaseRenewalCommand leaseRenewal,
        List<ExecutionEventPort.ArtifactReference> artifacts,
        String correlationId, String traceparent, String tracestate) {

    public ExecutionEventEnvelope(int schemaVersion, String type, UUID taskId,
            ExecutionEventPort.TaskExecutionCommand taskExecution,
            ExecutionEventPort.LeaseRenewalCommand leaseRenewal,
            List<ExecutionEventPort.ArtifactReference> artifacts) {
        this(schemaVersion, type, taskId, taskExecution, leaseRenewal, artifacts,
                taskExecution != null ? taskExecution.correlationId() : leaseRenewal.correlationId(),
                taskExecution != null ? taskExecution.traceparent() : leaseRenewal.traceparent(),
                taskExecution != null ? taskExecution.tracestate() : leaseRenewal.tracestate());
    }

    public ExecutionEventEnvelope {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requireText(type, "type");
        Objects.requireNonNull(taskId, "taskId");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        TraceContext context = new TraceContext(correlationId, traceparent, tracestate);
        correlationId = context.correlationId();
        traceparent = context.traceparent();
        tracestate = context.tracestate();
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
                null, artifacts, command.correlationId(), command.traceparent(), command.tracestate());
    }

    public static ExecutionEventEnvelope leaseRenewal(UUID taskId,
            ExecutionEventPort.LeaseRenewalCommand command) {
        return new ExecutionEventEnvelope(1, "LEASE_RENEWAL", taskId, null,
                Objects.requireNonNull(command, "command"), List.of(), command.correlationId(),
                command.traceparent(), command.tracestate());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
