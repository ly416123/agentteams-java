package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.SandboxAssignment;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayRuntimeAdapterSandboxReviewTest {
    @Test
    void exposesOnlyValidatedSandboxMetadataToRuntimeTask() {
        UUID taskId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String attemptId = "22222222-2222-2222-2222-222222222222";
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), ignored -> { }, Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", ignored -> { }, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        TaskAssigned assignment = TaskAssigned.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setTaskId(taskId.toString())
                        .setAttemptId(attemptId).setLeaseId("lease-1").build())
                .setTaskType("chat").setInputJson(ByteString.copyFromUtf8("{}"))
                .setSandbox(SandboxAssignment.newBuilder().setSandboxId("sandbox-1")
                        .setProviderSandboxId("provider-sandbox-1").setProfile("ISOLATED")
                        .setStatus("READY").setEndpointRef("sandbox://provider/sandbox-1")
                        .setExpiresAt(Timestamp.newBuilder().setSeconds(60).build())
                        .setOwnerTaskId(taskId.toString()).setOwnerAttemptId(attemptId))
                .build();

        assertThat(adapter.acceptAssignment(assignment).accepted()).isTrue();
        assertThat(runtime.status(taskId)).get().extracting(status -> status.task().metadata())
                .isEqualTo(Map.ofEntries(Map.entry("agentId", "agent-1"), Map.entry("attemptId", attemptId),
                        Map.entry("leaseId", "lease-1"),
                        Map.entry("sandboxId", "sandbox-1"),
                        Map.entry("providerSandboxId", "provider-sandbox-1"), Map.entry("profile", "ISOLATED"),
                        Map.entry("status", "READY"),
                        Map.entry("endpointRef", "sandbox://provider/sandbox-1"),
                        Map.entry("expiresAt", "1970-01-01T00:01:00Z"),
                        Map.entry("ownerTaskId", taskId.toString()), Map.entry("ownerAttemptId", attemptId)));
    }
}
