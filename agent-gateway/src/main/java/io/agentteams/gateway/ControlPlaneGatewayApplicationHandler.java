package io.agentteams.gateway;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.Timestamps;
import io.agentteams.contracts.v1.ArtifactRef;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.service.ExecutionEventService;
import io.agentteams.domain.task.FailureInfo;
import io.agentteams.domain.task.LeaseRenewalCommand;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bridges validated Gateway execution events into the control-plane domain service. */
public final class ControlPlaneGatewayApplicationHandler implements GatewayApplicationHandler {

    private static final String SOURCE = "gateway";
    private static final String ARTIFACT_CONTENT_TYPE = "application/octet-stream";

    private final ExecutionEventService executionEvents;
    private final Clock clock;

    public ControlPlaneGatewayApplicationHandler(ExecutionEventService executionEvents, Clock clock) {
        this.executionEvents = Objects.requireNonNull(executionEvents, "executionEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection, TaskAccepted event) {
        if (!event.getAccepted()) {
            throw invalid("accepted event must have accepted=true");
        }
        apply(connection, event.getMetadata(), TaskPhase.ACCEPTED, null, List.of());
    }

    @Override
    public void taskProgress(ConnectionRegistry.ConnectionSnapshot connection, TaskProgress event) {
        apply(connection, event.getMetadata(), TaskPhase.RUNNING, null, List.of());
    }

    @Override
    public void taskHeartbeat(ConnectionRegistry.ConnectionSnapshot connection, TaskHeartbeat event) {
        EventMetadata metadata = event.getMetadata();
        UUID taskId = uuid(metadata.getTaskId(), "task_id");
        UUID attemptId = uuid(metadata.getAttemptId(), "attempt_id");
        UUID leaseId = uuid(metadata.getLeaseId(), "lease_id");
        UUID eventId = uuid(metadata.getEventId(), "event_id");
        if (!connection.agentId().equals(metadata.getAgentId())) {
            throw invalid("agent_id does not match connection");
        }
        if (!event.hasLeaseExpiresAt() || !Timestamps.isValid(event.getLeaseExpiresAt())) {
            throw invalid("lease_expires_at is required and must be valid");
        }
        Instant at = occurredAt(metadata);
        Instant requestedExpiry = Instant.ofEpochSecond(event.getLeaseExpiresAt().getSeconds(),
                event.getLeaseExpiresAt().getNanos());
        executionEvents.renewLease(taskId, new LeaseRenewalCommand(eventId, metadata.getExpectedVersion(),
                attemptId, leaseId, at, requestedExpiry, connection.agentId(), SOURCE));
    }

    @Override
    public void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection, TaskCompleted event) {
        EventMetadata metadata = event.getMetadata();
        Instant occurredAt = occurredAt(metadata);
        apply(connection, metadata, TaskPhase.SUCCEEDED, null,
                event.getArtifactsList().stream().map(artifact -> artifact(artifact, metadata, occurredAt)).toList());
    }

    @Override
    public void taskFailed(ConnectionRegistry.ConnectionSnapshot connection, TaskFailed event) {
        EventMetadata metadata = event.getMetadata();
        String details = event.getDetails().isBlank() ? event.getMessage()
                : event.getMessage() + "\n" + event.getDetails();
        apply(connection, metadata, TaskPhase.FAILED,
                FailureInfo.fromRaw(event.getCode(), details), List.of());
    }

    private void apply(ConnectionRegistry.ConnectionSnapshot connection, EventMetadata metadata,
            TaskPhase phase, FailureInfo failure, List<ArtifactRecord> artifacts) {
        UUID taskId = uuid(metadata.getTaskId(), "task_id");
        UUID attemptId = uuid(metadata.getAttemptId(), "attempt_id");
        UUID leaseId = uuid(metadata.getLeaseId(), "lease_id");
        UUID eventId = uuid(metadata.getEventId(), "event_id");
        if (!connection.agentId().equals(metadata.getAgentId())) {
            throw invalid("agent_id does not match connection");
        }
        Instant at = occurredAt(metadata);
        TaskTransitionCommand command = phase == TaskPhase.FAILED
                ? TaskTransitionCommand.failed(eventId, metadata.getExpectedVersion(), attemptId, leaseId,
                        at, connection.agentId(), SOURCE, failure)
                : TaskTransitionCommand.forAttempt(eventId, metadata.getExpectedVersion(), phase, attemptId, leaseId,
                        at, connection.agentId(), SOURCE);
        executionEvents.apply(taskId, command, artifacts);
    }

    private static ArtifactRecord artifact(ArtifactRef ref, EventMetadata metadata, Instant at) {
        if (ref.getName().isBlank() || ref.getUri().isBlank() || ref.getSha256().isBlank()) {
            throw invalid("artifact name, uri and sha256 are required");
        }
        UUID taskId = uuid(metadata.getTaskId(), "task_id");
        UUID attemptId = uuid(metadata.getAttemptId(), "attempt_id");
        UUID id = UUID.nameUUIDFromBytes((metadata.getEventId() + "\n" + ref.getName() + "\n"
                + ref.getSha256()).getBytes(StandardCharsets.UTF_8));
        return new ArtifactRecord(id, taskId, attemptId, ref.getName(), ref.getUri(), ARTIFACT_CONTENT_TYPE,
                ref.getSizeBytes(), ref.getSha256(), "AVAILABLE", "{}", at, at, 0);
    }

    private static Instant occurredAt(EventMetadata metadata) {
        if (!metadata.hasOccurredAt() || !Timestamps.isValid(metadata.getOccurredAt())) {
            throw invalid("occurred_at is required and must be valid");
        }
        try {
            return Instant.ofEpochSecond(metadata.getOccurredAt().getSeconds(), metadata.getOccurredAt().getNanos());
        } catch (RuntimeException ex) {
            throw invalid("occurred_at is invalid");
        }
    }

    private static UUID uuid(String value, String field) {
        if (value.isBlank()) {
            throw invalid(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(field + " must be a UUID");
        }
    }

    private static GatewayExceptions.InvalidMessage invalid(String message) {
        return new GatewayExceptions.InvalidMessage(message);
    }
}
