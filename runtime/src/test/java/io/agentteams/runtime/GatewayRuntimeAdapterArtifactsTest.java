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

class GatewayRuntimeAdapterArtifactsTest {
    @Test
    void reportsProtocolArtifactsPlusResultEnvelopeOnCompletion() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        adapter.acceptAssignment(assignment(taskId));

        String output = "{\"summary\":\"done\",\"artifacts\":["
                + "{\"name\":\"报告.md\",\"content\":\"# 报告内容\"},"
                + "{\"name\":\"data/export.json\",\"content\":\"{\\\"value\\\": 42}\"}]}";
        adapter.complete(RuntimeResult.success(taskId, output, Instant.EPOCH));

        AgentMessage completion = completionMessage(messages);
        var artifacts = completion.getTaskCompleted().getArtifactsList();
        assertThat(artifacts).hasSize(3);
        assertThat(artifacts.get(0).getName()).isEqualTo("报告.md");
        assertThat(artifacts.get(0).getUri()).isEqualTo("memory://tasks/" + taskId + "/报告.md");
        assertThat(artifacts.get(0).getSha256()).hasSize(64);
        assertThat(artifacts.get(1).getName()).isEqualTo("data_export.json");
        // The envelope always closes the manifest.
        assertThat(artifacts.get(2).getName()).isEqualTo("result.json");
        assertThat(artifacts.get(2).getUri()).contains("result.json");
        assertThat(artifacts.get(2).getSizeBytes()).isPositive();
    }

    @Test
    void promotesFencedProtocolOutputAndSkipsInvalidEntries() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        adapter.acceptAssignment(assignment(taskId));

        String output = "```json\n"
                + "{\"artifacts\":[{\"name\":\"plan.md\",\"content\":\"plan body\"},{\"content\":\"no name\"}]}\n"
                + "```";
        adapter.complete(RuntimeResult.success(taskId, output, Instant.EPOCH));

        var artifacts = completionMessage(messages).getTaskCompleted().getArtifactsList();
        assertThat(artifacts).extracting(a -> a.getName())
                .containsExactly("plan.md", "result.json");
    }

    @Test
    void fallsBackToOutputMdForPlainTextResults() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        adapter.acceptAssignment(assignment(taskId));

        adapter.complete(RuntimeResult.success(taskId, "plain assistant answer", Instant.EPOCH,
                new RuntimeCallUsage("qwenpaw", "deepseek-chat", 1200, 12479, 14)));

        var completed = completionMessage(messages).getTaskCompleted();
        var artifacts = completed.getArtifactsList();
        assertThat(artifacts).extracting(a -> a.getName()).containsExactly("output.md", "result.json");
        assertThat(artifacts.get(0).getSha256()).hasSize(64);
        assertThat(artifacts.get(0).getSizeBytes()).isEqualTo("plain assistant answer".getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length);
        // The envelope records the measured model call usage for token accounting.
        assertThat(artifacts.get(1).getSha256()).hasSize(64);
        assertThat(completed.hasModelCall()).isTrue();
        assertThat(completed.getModelCall().getPromptTokens()).isEqualTo(12479);
        assertThat(completed.getModelCall().getCompletionTokens()).isEqualTo(14);
    }

    @Test
    void failedResultsDoNotCarryArtifacts() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        adapter.acceptAssignment(assignment(taskId));

        adapter.complete(RuntimeResult.failure(taskId, "boom", Instant.EPOCH));

        assertThat(messages).extracting(AgentMessage::getPayloadCase)
                .contains(AgentMessage.PayloadCase.TASK_FAILED);
        assertThat(messages).extracting(AgentMessage::getPayloadCase)
                .doesNotContain(AgentMessage.PayloadCase.TASK_COMPLETED);
    }

    private static AgentMessage completionMessage(List<AgentMessage> messages) {
        return messages.stream()
                .filter(message -> message.getPayloadCase() == AgentMessage.PayloadCase.TASK_COMPLETED)
                .findFirst()
                .orElseThrow();
    }

    private static TaskAssigned assignment(UUID taskId) {
        return TaskAssigned.newBuilder().setMetadata(EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setAgentId("agent-1").setTaskId(taskId.toString())
                .setAttemptId(UUID.randomUUID().toString()).setLeaseId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.getDefaultInstance()).build()).setTaskType("chat")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .setLeaseExpiresAt(Timestamp.getDefaultInstance()).build();
    }
}
