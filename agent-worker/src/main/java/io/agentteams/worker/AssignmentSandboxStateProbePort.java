package io.agentteams.worker;

import io.agentteams.application.api.SandboxStatus;
import io.agentteams.contracts.v1.SandboxAssignment;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only sandbox projection received with an assignment and used by AgentScope wiring. */
public final class AssignmentSandboxStateProbePort implements SandboxStateProbePort {
    private final Clock clock;
    private final Map<UUID, Projection> projections = new ConcurrentHashMap<>();

    public AssignmentSandboxStateProbePort(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void register(TaskAssigned assignment) {
        Objects.requireNonNull(assignment, "assignment");
        UUID taskId = UUID.fromString(assignment.getMetadata().getTaskId());
        if (!assignment.hasSandbox()) {
            projections.remove(taskId);
            return;
        }
        SandboxAssignment sandbox = assignment.getSandbox();
        UUID sandboxId = parse(sandbox.getSandboxId(), "sandboxId");
        UUID ownerTaskId = parse(sandbox.getOwnerTaskId(), "ownerTaskId");
        UUID ownerAttemptId = parse(sandbox.getOwnerAttemptId(), "ownerAttemptId");
        if (!taskId.equals(ownerTaskId) || !assignment.getMetadata().getAttemptId().equals(ownerAttemptId.toString())) {
            throw new IllegalArgumentException("sandbox assignment owner does not match task");
        }
        Instant expiresAt = Instant.ofEpochSecond(sandbox.getExpiresAt().getSeconds(),
                sandbox.getExpiresAt().getNanos());
        projections.put(taskId, new Projection(sandboxId, ownerAttemptId,
                SandboxStatus.valueOf(sandbox.getStatus()), sandbox.getEndpointRef(), expiresAt));
    }

    public void forget(UUID taskId) {
        projections.remove(Objects.requireNonNull(taskId, "taskId"));
    }

    @Override
    public SandboxExecutionState inspect(UUID sandboxId, UUID taskId, UUID attemptId) {
        Projection projection = projections.get(Objects.requireNonNull(taskId, "taskId"));
        if (projection == null || !projection.sandboxId().equals(sandboxId)
                || !projection.attemptId().equals(attemptId)
                || !clock.instant().isBefore(projection.expiresAt())) {
            throw new IllegalStateException("sandbox assignment projection is not usable");
        }
        return new SandboxExecutionState(projection.status(), projection.endpointRef(), projection.expiresAt());
    }

    private static UUID parse(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private record Projection(UUID sandboxId, UUID attemptId, SandboxStatus status,
            String endpointRef, Instant expiresAt) { }
}
