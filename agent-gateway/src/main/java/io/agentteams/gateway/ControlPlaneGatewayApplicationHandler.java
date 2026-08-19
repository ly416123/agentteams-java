package io.agentteams.gateway;

import com.google.protobuf.util.Timestamps;
import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.ExecutionEventPort.ArtifactReference;
import io.agentteams.application.api.ExecutionEventPort.ExecutionPhase;
import io.agentteams.application.api.ExecutionEventPort.LeaseRenewalCommand;
import io.agentteams.application.api.ExecutionEventPort.TaskExecutionCommand;
import io.agentteams.contracts.v1.ArtifactRef;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bridges validated Gateway execution events into the control-plane domain service. */
public final class ControlPlaneGatewayApplicationHandler implements GatewayApplicationHandler {

    private static final String SOURCE = "gateway";
    private static final String ARTIFACT_CONTENT_TYPE = "application/octet-stream";

    private final ExecutionEventPort executionEvents;
    private final ConfigEventPort configEvents;
    private final Clock clock;

    public ControlPlaneGatewayApplicationHandler(ExecutionEventPort executionEvents, Clock clock) {
        this(executionEvents, command -> { }, clock);
    }

    public ControlPlaneGatewayApplicationHandler(ExecutionEventPort executionEvents,
            ConfigEventPort configEvents, Clock clock) {
        this.executionEvents = Objects.requireNonNull(executionEvents, "executionEvents");
        this.configEvents = Objects.requireNonNull(configEvents, "configEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void configApplied(ConnectionRegistry.ConnectionSnapshot connection,
            io.agentteams.contracts.v1.ConfigApplied event) {
        EventMetadata metadata = event.getMetadata();
        UUID eventId = uuid(metadata.getEventId(), "event_id");
        UUID bindingId = uuid(event.getBindingId(), "binding_id");
        UUID snapshotId = uuid(event.getSnapshotId(), "snapshot_id");
        if (!connection.agentId().equals(metadata.getAgentId())) {
            throw invalid("agent_id does not match connection");
        }
        if (event.getConfigVersion() <= 0) {
            throw invalid("config_version must be positive");
        }
        configEvents.applied(new ConfigEventPort.ConfigAppliedCommand(eventId, bindingId, snapshotId,
                uuid(connection.agentId(), "agent_id"), event.getConfigVersion(), event.getApplied(),
                event.getErrorMessage(), occurredAt(metadata), SOURCE,
                GrpcTransportIdentity.currentCorrelationId()));
    }

    @Override
    public void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection, TaskAccepted event) {
        if (!event.getAccepted()) {
            // A runtime may reject a redelivered assignment because it is
            // already running another attempt. This is a valid delivery
            // outcome, not a malformed execution event; acknowledge it and
            // leave the Control Plane aggregate unchanged.
            return;
        }
        apply(connection, event.getMetadata(), ExecutionPhase.ACCEPTED, "", "", List.of());
    }

    @Override
    public void taskProgress(ConnectionRegistry.ConnectionSnapshot connection, TaskProgress event) {
        apply(connection, event.getMetadata(), ExecutionPhase.RUNNING, "", "", List.of());
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
                attemptId, leaseId, at, requestedExpiry, connection.agentId(), SOURCE,
                GrpcTransportIdentity.currentCorrelationId()));
    }

    @Override
    public void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection, TaskCompleted event) {
        EventMetadata metadata = event.getMetadata();
        Instant occurredAt = occurredAt(metadata);
        apply(connection, metadata, ExecutionPhase.SUCCEEDED, "", "",
                event.getArtifactsList().stream().map(artifact -> artifact(artifact, metadata, occurredAt)).toList());
    }

    @Override
    public void taskFailed(ConnectionRegistry.ConnectionSnapshot connection, TaskFailed event) {
        EventMetadata metadata = event.getMetadata();
        String details = event.getDetails().isBlank() ? event.getMessage()
                : event.getMessage() + "\n" + event.getDetails();
        apply(connection, metadata, ExecutionPhase.FAILED, event.getCode(), details, List.of());
    }

    private void apply(ConnectionRegistry.ConnectionSnapshot connection, EventMetadata metadata,
            ExecutionPhase phase, String failureCode, String failureMessage, List<ArtifactReference> artifacts) {
        UUID taskId = uuid(metadata.getTaskId(), "task_id");
        UUID attemptId = uuid(metadata.getAttemptId(), "attempt_id");
        UUID leaseId = uuid(metadata.getLeaseId(), "lease_id");
        UUID eventId = uuid(metadata.getEventId(), "event_id");
        if (!connection.agentId().equals(metadata.getAgentId())) {
            throw invalid("agent_id does not match connection");
        }
        Instant at = occurredAt(metadata);
        executionEvents.apply(taskId, new TaskExecutionCommand(eventId, metadata.getExpectedVersion(), attemptId,
                leaseId, at, connection.agentId(), SOURCE, phase, failureCode, failureMessage,
                GrpcTransportIdentity.currentCorrelationId()), artifacts);
    }

    private static ArtifactReference artifact(ArtifactRef ref, EventMetadata metadata, Instant at) {
        if (ref.getName().isBlank() || ref.getUri().isBlank() || ref.getSha256().isBlank()) {
            throw invalid("artifact name, uri and sha256 are required");
        }
        return new ArtifactReference(ref.getName(), ref.getUri(), ARTIFACT_CONTENT_TYPE,
                ref.getSizeBytes(), ref.getSha256(), "{}");
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
