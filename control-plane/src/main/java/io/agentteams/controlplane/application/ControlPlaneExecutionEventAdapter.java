package io.agentteams.controlplane.application;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.application.api.ExecutionEventPort.ExecutionPhase;
import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.service.ExecutionEventService;
import io.agentteams.domain.task.FailureInfo;
import io.agentteams.domain.task.LeaseRenewalCommand;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Keeps domain and persistence types behind the application boundary. */
public final class ControlPlaneExecutionEventAdapter implements ExecutionEventPort {
    private final ExecutionEventService executionEvents;

    public ControlPlaneExecutionEventAdapter(ExecutionEventService executionEvents) {
        this.executionEvents = Objects.requireNonNull(executionEvents, "executionEvents");
    }

    @Override
    public void apply(UUID taskId, TaskExecutionCommand command, List<ArtifactReference> artifacts) {
        Objects.requireNonNull(command, "command");
        TaskTransitionCommand transition = command.phase() == ExecutionPhase.FAILED
                ? TaskTransitionCommand.failed(command.eventId(), command.expectedVersion(), command.attemptId(),
                        command.leaseId(), command.occurredAt(), command.agentId(), command.source(),
                        FailureInfo.fromRaw(command.failureCode(), command.failureMessage()))
                : TaskTransitionCommand.forAttempt(command.eventId(), command.expectedVersion(),
                        TaskPhase.valueOf(command.phase().name()), command.attemptId(), command.leaseId(),
                        command.occurredAt(), command.agentId(), command.source());
        executionEvents.apply(taskId, transition, artifacts.stream()
                .map(artifact -> toRecord(taskId, command.attemptId(), command.eventId(), command.occurredAt(), artifact))
                .toList());
    }

    @Override
    public void renewLease(UUID taskId, io.agentteams.application.api.ExecutionEventPort.LeaseRenewalCommand command) {
        Objects.requireNonNull(command, "command");
        executionEvents.renewLease(taskId, new io.agentteams.domain.task.LeaseRenewalCommand(command.eventId(), command.expectedVersion(),
                command.attemptId(), command.leaseId(), command.occurredAt(), command.requestedExpiry(),
                command.agentId(), command.source()));
    }

    private static ArtifactRecord toRecord(UUID taskId, UUID attemptId, UUID eventId, Instant at,
            ArtifactReference artifact) {
        UUID id = UUID.nameUUIDFromBytes((eventId + "\n" + artifact.name() + "\n" + artifact.sha256())
                .getBytes(StandardCharsets.UTF_8));
        return new ArtifactRecord(id, taskId, attemptId, artifact.name(), artifact.storageKey(),
                artifact.contentType(), artifact.sizeBytes(), artifact.sha256(), "AVAILABLE",
                artifact.metadataJson(), at, at, 0);
    }
}
