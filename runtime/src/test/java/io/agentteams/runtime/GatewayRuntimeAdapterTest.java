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

    @Test
    void registersAssignmentBeforeRuntimeCanCompleteSynchronously() {
        List<AgentMessage> messages = new ArrayList<>();
        ImmediateCompletionRuntime runtime = new ImmediateCompletionRuntime();
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        runtime.start(new AgentRuntimeContext("qwenpaw", 1, Clock.systemUTC(), adapter::complete, java.util.Map.of()));
        UUID taskId = UUID.randomUUID();
        TaskAssigned assignment = assignment(taskId);

        assertThat(adapter.acceptAssignment(assignment).accepted()).isTrue();
        assertThat(messages).extracting(AgentMessage::getPayloadCase)
                .containsExactlyInAnyOrder(AgentMessage.PayloadCase.TASK_COMPLETED,
                        AgentMessage.PayloadCase.TASK_ACCEPTED);
    }

    @Test
    void reportsRuntimeRejectionToGateway() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.systemUTC());

        TaskAssigned first = assignment(UUID.randomUUID());
        assertThat(adapter.acceptAssignment(first).accepted()).isTrue();
        messages.clear();
        TaskAssigned second = assignment(UUID.randomUUID());
        RuntimeSubmission submission = adapter.acceptAssignment(second);

        assertThat(submission.accepted()).isFalse();
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getPayloadCase()).isEqualTo(AgentMessage.PayloadCase.TASK_ACCEPTED);
            assertThat(message.getTaskAccepted().getAccepted()).isFalse();
            assertThat(message.getTaskAccepted().getRejectionReason()).isNotBlank();
        });
    }

    @Test
    void replacesACompletedRuntimeTaskForANewAttempt() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.systemUTC());
        UUID taskId = UUID.randomUUID();
        TaskAssigned first = assignment(taskId);

        assertThat(adapter.acceptAssignment(first).accepted()).isTrue();
        assertThat(adapter.complete(RuntimeResult.success(taskId, "first", Instant.EPOCH)))
                .isEqualTo(CompletionStatus.COMPLETED);

        TaskAssigned retry = first.toBuilder().setMetadata(first.getMetadata().toBuilder()
                .setAttemptId(UUID.randomUUID().toString()).setLeaseId(UUID.randomUUID().toString()).build()).build();
        assertThat(adapter.acceptAssignment(retry).accepted()).isTrue();
        assertThat(adapter.complete(RuntimeResult.success(taskId, "retry", Instant.EPOCH)))
                .isEqualTo(CompletionStatus.COMPLETED);
    }

    @Test
    void advancesExecutionVersionWhenReportingProgress() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID taskId = UUID.randomUUID();

        assertThat(adapter.acceptAssignment(assignment(taskId)).accepted()).isTrue();
        adapter.progress(taskId, 0, "running", "started");
        adapter.complete(RuntimeResult.success(taskId, "done", Instant.EPOCH));

        assertThat(messages).extracting(AgentMessage::getPayloadCase)
                .containsExactly(AgentMessage.PayloadCase.TASK_ACCEPTED,
                        AgentMessage.PayloadCase.TASK_PROGRESS, AgentMessage.PayloadCase.TASK_COMPLETED);
        assertThat(messages.get(1).getTaskProgress().getMetadata().getExpectedVersion()).isEqualTo(1);
        assertThat(messages.get(2).getTaskCompleted().getMetadata().getExpectedVersion()).isEqualTo(2);
    }

    @Test
    void carriesOperationalDimensionsIntoRuntimeTaskMetadata() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", message -> { }, runtime,
                Clock.systemUTC());
        UUID taskId = UUID.randomUUID();
        TaskAssigned assignment = assignment(taskId).toBuilder()
                .setTenantId("tenant-a").setProjectId("project-a").setTeamId("team-a")
                .setToolId("create_task").setQuotaId("quota-a").setQuotaDimension("daily_tokens").build();

        assertThat(adapter.acceptAssignment(assignment).accepted()).isTrue();
        assertThat(runtime.status(taskId)).get().extracting(status -> status.task().metadata())
                .isEqualTo(java.util.Map.of("agentId", "agent-1", "attemptId",
                        assignment.getMetadata().getAttemptId(), "leaseId", assignment.getMetadata().getLeaseId(),
                        "tenantId", "tenant-a", "projectId", "project-a", "teamId", "team-a",
                "toolId", "create_task", "quotaId", "quota-a", "quotaDimension", "daily_tokens"));
    }

    @Test
    void carriesRuntimeUsageAndDimensionsOnTerminalEvent() {
        List<AgentMessage> messages = new ArrayList<>();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(new AgentRuntimeContext("fake", 1, Clock.systemUTC(), result -> { }, java.util.Map.of()));
        GatewayRuntimeAdapter adapter = new GatewayRuntimeAdapter("agent-1", messages::add, runtime,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID taskId = UUID.randomUUID();
        TaskAssigned assignment = assignment(taskId).toBuilder().setTenantId("tenant-a").setProjectId("project-a")
                .setTeamId("team-a").setToolId("create_task").setQuotaId("quota-a")
                .setQuotaDimension("daily_tokens").build();

        assertThat(adapter.acceptAssignment(assignment).accepted()).isTrue();
        adapter.complete(RuntimeResult.success(taskId, "done", Instant.EPOCH,
                new RuntimeCallUsage("qwen", "qwen-plus", 42, 3, 5)));

        assertThat(messages).last().satisfies(message -> {
            assertThat(message.getTaskCompleted().getModelCall().getProvider()).isEqualTo("qwen");
            assertThat(message.getTaskCompleted().getModelCall().getPromptTokens()).isEqualTo(3);
            assertThat(message.getTaskCompleted().getModelCall().getWorkerId()).isEqualTo("agent-1");
            assertThat(message.getTaskCompleted().getModelCall().getTeamId()).isEqualTo("team-a");
            assertThat(message.getTaskCompleted().getModelCall().getQuotaDimension()).isEqualTo("daily_tokens");
        });
    }

    private static TaskAssigned assignment(UUID taskId) {
        return TaskAssigned.newBuilder().setMetadata(EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setAgentId("agent-1").setTaskId(taskId.toString())
                .setAttemptId(UUID.randomUUID().toString()).setLeaseId(UUID.randomUUID().toString())
                .setOccurredAt(Timestamp.getDefaultInstance()).build()).setTaskType("chat")
                .setInputJson(com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .setLeaseExpiresAt(Timestamp.getDefaultInstance()).build();
    }

    private static final class ImmediateCompletionRuntime implements AgentRuntime {
        private RuntimeTask task;
        private RuntimeResultSink sink;

        @Override
        public void start(AgentRuntimeContext context) { sink = context.resultSink(); }

        @Override
        public RuntimeSubmission submit(RuntimeTask task) {
            this.task = task;
            sink.accept(RuntimeResult.success(task.id(), "done", Instant.EPOCH));
            return RuntimeSubmission.acceptedSubmission();
        }

        @Override
        public CompletionStatus complete(RuntimeResult result) { return CompletionStatus.COMPLETED; }

        @Override
        public boolean cancel(UUID taskId) { return false; }

        @Override
        public java.util.Optional<RuntimeStatus> status(UUID taskId) { return java.util.Optional.empty(); }

        @Override
        public RuntimeSnapshot snapshot() { return new RuntimeSnapshot(task == null ? 0 : 1, task == null ? 0 : 1); }

        @Override
        public void stop() { }
    }
}
