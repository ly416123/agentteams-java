package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAssigned;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayRuntimeAdapterTest {
    @Test
    void mapsAssignmentAndSendsOnlyOneCompletionForDuplicateEvents() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        TaskAssigned assignment = TaskAssigned.newBuilder().setMetadata(EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setAgentId("agent-1").setTaskId(taskId.toString())
                .setAttemptId(UUID.randomUUID().toString()).setLeaseId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.getDefaultInstance()).build()).setTaskType("chat")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .setLeaseExpiresAt(Timestamp.getDefaultInstance()).build();

        assertThat(adapter.acceptAssignment(assignment).accepted()).isTrue();
        RuntimeResult result = RuntimeResult.success(taskId, "done", Instant.EPOCH);
        assertThat(adapter.complete(result)).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(adapter.complete(result)).isEqualTo(CompletionStatus.DUPLICATE);
        assertThat(messages).extracting(AgentMessage::getPayloadCase)
                .containsExactly(AgentMessage.PayloadCase.TASK_ACCEPTED, AgentMessage.PayloadCase.TASK_COMPLETED);
    }
}
