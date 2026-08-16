package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskProgress;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.service.ExecutionEventService;
import io.agentteams.domain.task.FailureInfo;
import io.agentteams.domain.task.LeaseRenewalCommand;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ControlPlaneGatewayApplicationHandlerTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final UUID LEASE_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void mapsAcceptedProgressCompletedAndFailedToDomainTransitions() {
        ExecutionEventService service = mock(ExecutionEventService.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(service, clock());
        ConnectionRegistry.ConnectionSnapshot connection = connection();

        handler.taskAccepted(connection, TaskAccepted.newBuilder().setMetadata(metadata("accepted", 1)).setAccepted(true).build());
        handler.taskProgress(connection, TaskProgress.newBuilder().setMetadata(metadata("progress", 2)).setStatus("running").build());
        handler.taskCompleted(connection, TaskCompleted.newBuilder().setMetadata(metadata("completed", 3)).build());
        handler.taskFailed(connection, TaskFailed.newBuilder().setMetadata(metadata("failed", 4))
                .setCode("RUNTIME_ERROR").setMessage("token=secret").setDetails("password: hidden").build());

        ArgumentCaptor<TaskTransitionCommand> commands = ArgumentCaptor.forClass(TaskTransitionCommand.class);
        verify(service, org.mockito.Mockito.times(4)).apply(eq(TASK_ID), commands.capture(), any());
        assertThat(commands.getAllValues()).extracting(TaskTransitionCommand::targetPhase)
                .containsExactly(TaskPhase.ACCEPTED, TaskPhase.RUNNING, TaskPhase.SUCCEEDED, TaskPhase.FAILED);
        assertThat(commands.getAllValues()).extracting(TaskTransitionCommand::expectedVersion)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.actor()).isEqualTo("agent-1");
            assertThat(command.source()).isEqualTo("gateway");
            assertThat(command.occurredAt()).isEqualTo(AT);
        });
        TaskTransitionCommand failure = commands.getAllValues().get(3);
        assertThat(failure.failure().redactedMessage()).doesNotContain("secret", "hidden")
                .contains("[REDACTED]");
    }

    @Test
    void mapsArtifactRefToArtifactRecord() {
        ExecutionEventService service = mock(ExecutionEventService.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(service, clock());
        EventMetadata metadata = metadata("completed-artifact", 7);
        handler.taskCompleted(connection(), TaskCompleted.newBuilder().setMetadata(metadata)
                .setResultJson(ByteString.copyFromUtf8("{}"))
                .addArtifacts(io.agentteams.contracts.v1.ArtifactRef.newBuilder()
                        .setName("result.json").setUri("s3://bucket/result.json").setSha256("abc").setSizeBytes(12).build())
                .build());

        ArgumentCaptor<List<ArtifactRecord>> artifacts = ArgumentCaptor.forClass(List.class);
        verify(service).apply(eq(TASK_ID), any(), artifacts.capture());
        ArtifactRecord record = artifacts.getValue().get(0);
        assertThat(record.taskId()).isEqualTo(TASK_ID);
        assertThat(record.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(record.name()).isEqualTo("result.json");
        assertThat(record.storageKey()).isEqualTo("s3://bucket/result.json");
        assertThat(record.sha256()).isEqualTo("abc");
        assertThat(record.sizeBytes()).isEqualTo(12);
        assertThat(record.status()).isEqualTo("AVAILABLE");
        assertThat(record.metadataJson()).isEqualTo("{}");
    }

    @Test
    void mapsHeartbeatToLeaseRenewalInsteadOfAStateTransition() {
        ExecutionEventService service = mock(ExecutionEventService.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(service, clock());
        Instant renewedUntil = AT.plusSeconds(600);

        handler.taskHeartbeat(connection(), io.agentteams.contracts.v1.TaskHeartbeat.newBuilder()
                .setMetadata(metadata("heartbeat", 5))
                .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(renewedUntil.getEpochSecond()).build())
                .setStatus("running")
                .build());

        ArgumentCaptor<LeaseRenewalCommand> command = ArgumentCaptor.forClass(LeaseRenewalCommand.class);
        verify(service).renewLease(eq(TASK_ID), command.capture());
        assertThat(command.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(command.getValue().leaseId()).isEqualTo(LEASE_ID);
        assertThat(command.getValue().requestedExpiry()).isEqualTo(renewedUntil);
        assertThat(command.getValue().expectedVersion()).isEqualTo(5);
    }

    @Test
    void rejectsMissingOrInvalidRequiredMetadata() {
        ExecutionEventService service = mock(ExecutionEventService.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(service, clock());
        EventMetadata invalid = metadata("bad", 1).toBuilder().setAttemptId("not-a-uuid").build();

        assertThatThrownBy(() -> handler.taskProgress(connection(),
                TaskProgress.newBuilder().setMetadata(invalid).build()))
                .isInstanceOf(GatewayExceptions.InvalidMessage.class)
                .hasMessageContaining("attempt_id");
        assertThatThrownBy(() -> handler.taskAccepted(connection(), TaskAccepted.newBuilder()
                .setMetadata(metadata("rejected", 1).toBuilder().clearLeaseId().build()).setAccepted(true).build()))
                .isInstanceOf(GatewayExceptions.InvalidMessage.class)
                .hasMessageContaining("lease_id");
    }

    @Test
    void rejectsMissingOrInvalidUuidIdentityFields() {
        ExecutionEventService service = mock(ExecutionEventService.class);
        ControlPlaneGatewayApplicationHandler handler = new ControlPlaneGatewayApplicationHandler(service, clock());
        String[] fields = {"eventId", "taskId", "attemptId", "leaseId"};

        for (String field : fields) {
            EventMetadata.Builder builder = metadata("event-id", 1).toBuilder();
            switch (field) {
                case "eventId" -> builder.setEventId("not-a-uuid");
                case "taskId" -> builder.setTaskId("not-a-uuid");
                case "attemptId" -> builder.setAttemptId("not-a-uuid");
                case "leaseId" -> builder.setLeaseId("not-a-uuid");
                default -> throw new AssertionError("unexpected field: " + field);
            }
            EventMetadata invalid = builder.build();
            assertThatThrownBy(() -> handler.taskProgress(connection(),
                    TaskProgress.newBuilder().setMetadata(invalid).build()))
                .isInstanceOf(GatewayExceptions.InvalidMessage.class)
                    .hasMessageContaining(field.replace("Id", "_id").toLowerCase());
        }
    }

    private static EventMetadata metadata(String eventId, long version) {
        return EventMetadata.newBuilder().setEventId(UUID.nameUUIDFromBytes(eventId.getBytes()).toString()).setAgentId("agent-1")
                .setTaskId(TASK_ID.toString()).setAttemptId(ATTEMPT_ID.toString()).setLeaseId(LEASE_ID.toString())
                .setExpectedVersion(version).setOccurredAt(Timestamp.newBuilder()
                        .setSeconds(AT.getEpochSecond()).setNanos(AT.getNano()).build()).build();
    }

    private static ConnectionRegistry.ConnectionSnapshot connection() {
        return new ConnectionRegistry.ConnectionSnapshot(UUID.randomUUID(), "agent-1", "fake", "1", java.util.Map.of(), AT, 0);
    }

    private static Clock clock() {
        return Clock.fixed(AT, ZoneOffset.UTC);
    }
}
