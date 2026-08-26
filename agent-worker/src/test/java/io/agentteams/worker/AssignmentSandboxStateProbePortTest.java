package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.SandboxAssignment;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.application.api.SandboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssignmentSandboxStateProbePortTest {
    @Test
    void observesAndForgetsTheVerifiedAssignmentProjection() {
        UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID attemptId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID sandboxId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AssignmentSandboxStateProbePort probe = new AssignmentSandboxStateProbePort(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
        TaskAssigned assignment = TaskAssigned.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setTaskId(taskId.toString())
                        .setAttemptId(attemptId.toString()).build())
                .setSandbox(SandboxAssignment.newBuilder().setSandboxId(sandboxId.toString())
                        .setOwnerTaskId(taskId.toString()).setOwnerAttemptId(attemptId.toString())
                        .setStatus("READY").setEndpointRef("sandbox://provider/a")
                        .setExpiresAt(Timestamp.newBuilder().setSeconds(
                                Instant.parse("2026-08-26T00:01:00Z").getEpochSecond()).build()).build())
                .build();

        probe.register(assignment);

        assertThat(probe.inspect(sandboxId, taskId, attemptId).status()).isEqualTo(SandboxStatus.READY);
        probe.forget(taskId);
        assertThatThrownBy(() -> probe.inspect(sandboxId, taskId, attemptId))
                .isInstanceOf(IllegalStateException.class);
    }
}
