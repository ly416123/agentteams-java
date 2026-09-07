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

class GatewayRuntimeAdapterArtifactUploadTest {
    private static final String ATTEMPT_ID = UUID.randomUUID().toString();

    @Test
    void uploadsArtifactsAndReferencesTheStoredObject() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        RecordingUploadPort uploads = new RecordingUploadPort();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), uploads);
        adapter.acceptAssignment(assignment(taskId));

        adapter.complete(RuntimeResult.success(taskId,
                "{\"artifacts\":[{\"name\":\"plan.md\",\"content\":\"plan body\"}]}", Instant.EPOCH));

        var artifacts = completionMessage(messages).getTaskCompleted().getArtifactsList();
        assertThat(artifacts).hasSize(2);
        String expectedKey = "tasks/" + taskId + "/attempts/" + ATTEMPT_ID + "/artifacts/plan.md";
        assertThat(artifacts.get(0).getUri()).isEqualTo(expectedKey);
        assertThat(artifacts.get(1).getName()).isEqualTo("result.json");
        assertThat(artifacts.get(1).getUri()).startsWith("tasks/" + taskId + "/attempts/" + ATTEMPT_ID + "/");
        assertThat(uploads.calls).hasSize(2);
        RecordingUploadPort.Call first = uploads.calls.get(0);
        assertThat(first.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(first.contentType()).isEqualTo("text/markdown");
        assertThat(new String(first.payload(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("plan body");
        assertThat(uploads.calls.get(1).contentType()).isEqualTo("application/json");
    }

    @Test
    void fallsBackToTheMemoryReferenceWhenUploadFails() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        RecordingUploadPort uploads = new RecordingUploadPort();
        uploads.error = new IllegalStateException("minio unavailable");
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), uploads);
        adapter.acceptAssignment(assignment(taskId));

        adapter.complete(RuntimeResult.success(taskId,
                "{\"artifacts\":[{\"name\":\"plan.md\",\"content\":\"plan body\"}]}", Instant.EPOCH));

        var artifacts = completionMessage(messages).getTaskCompleted().getArtifactsList();
        assertThat(artifacts.get(0).getUri()).isEqualTo("memory://tasks/" + taskId + "/plan.md");
        // The task still completes; every artifact attempted the upload.
        assertThat(uploads.calls).hasSize(2);
    }

    @Test
    void keepsTheMemoryReferenceWithoutAnUploadPort() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        adapter.acceptAssignment(assignment(taskId));

        adapter.complete(RuntimeResult.success(taskId, "plain output", Instant.EPOCH));

        var artifacts = completionMessage(messages).getTaskCompleted().getArtifactsList();
        assertThat(artifacts.get(0).getUri()).isEqualTo("memory://tasks/" + taskId + "/output.md");
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
                .setAttemptId(ATTEMPT_ID).setLeaseId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.getDefaultInstance()).build()).setTaskType("chat")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .setLeaseExpiresAt(Timestamp.getDefaultInstance()).build();
    }

    private static final class RecordingUploadPort implements RuntimeArtifactUploadPort {
        final List<Call> calls = new ArrayList<>();
        RuntimeException error;

        record Call(String taskId, String attemptId, String name, String contentType, byte[] payload) {
        }

        @Override
        public UploadedArtifact upload(String taskId, String attemptId, String name, String contentType,
                byte[] payload) {
            calls.add(new Call(taskId, attemptId, name, contentType, payload));
            if (error != null) {
                throw error;
            }
            return new UploadedArtifact("tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/" + name,
                    "http://minio/get");
        }
    }
}
