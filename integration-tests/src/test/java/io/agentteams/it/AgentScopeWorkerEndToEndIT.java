package io.agentteams.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.controlplane.sandbox.FakeSandboxRuntime;
import io.agentteams.runtime.AgentChannelPort;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.GatewayRuntimeAdapter;
import io.agentteams.runtime.RuntimeCallUsage;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeStatus;
import io.agentteams.runtime.RuntimeTaskState;
import io.agentteams.worker.agentscope.AgentScopeExecutionEvent;
import io.agentteams.worker.agentscope.AgentScopeRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Pure-Java AgentScope worker acceptance tests; no Docker, network, or model credentials. */
class AgentScopeWorkerEndToEndIT {

    private final List<ScenarioHarness> harnesses = new ArrayList<>();

    @AfterEach
    void closeHarnesses() {
        harnesses.forEach(ScenarioHarness::close);
    }

    @Test
    void projectsTaskAttemptLeaseAndFixedFakeModelUsageOnSuccess() throws Exception {
        ScenarioHarness harness = harness(Scenario.SUCCESS);

        harness.submitAssignment();
        harness.awaitResult();

        assertThat(harness.projection).isEqualTo(new Projection(
                TaskState.SUCCEEDED, AttemptState.SUCCEEDED, LeaseState.RELEASED));
        assertThat(harness.results).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("fixed fake answer");
            assertThat(result.callUsage()).isEqualTo(
                    new RuntimeCallUsage("fake-agentscope", "fake-model", 0, 11, 7));
        });
        assertThat(harness.events).extracting(AgentScopeExecutionEvent::kind)
                .contains(AgentScopeExecutionEvent.Kind.AGENT_STARTED,
                        AgentScopeExecutionEvent.Kind.MODEL_CALL_COMPLETED,
                        AgentScopeExecutionEvent.Kind.AGENT_RESULT,
                        AgentScopeExecutionEvent.Kind.AGENT_ENDED);
        assertThat(harness.events).allSatisfy(event -> {
            assertThat(event.taskId()).isEqualTo(harness.taskId.toString());
            assertThat(event.attemptId()).isEqualTo(harness.attemptId);
            assertThat(event.leaseId()).isEqualTo(harness.leaseId);
            assertThat(event.runtime()).isEqualTo("AGENTSCOPE");
        });
        assertThat(harness.acceptedMessages).singleElement()
                .extracting(message -> message.getTaskAccepted().getAccepted()).isEqualTo(true);
        harness.assertSandboxReclaimed(SandboxTerminationReason.TASK_COMPLETED);
    }

    @Test
    void projectsToolRefusalAsFailedAttemptWithoutSuccessfulToolExecution() throws Exception {
        ScenarioHarness harness = harness(Scenario.TOOL_REJECTED);

        harness.submitAssignment();
        harness.awaitResult();

        assertThat(harness.projection).isEqualTo(
                new Projection(TaskState.FAILED, AttemptState.FAILED, LeaseState.RELEASED));
        assertThat(harness.results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isEqualTo("AgentScope execution failed");
        });
        assertThat(harness.events).anySatisfy(event ->
                assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.ERROR));
        assertThat(harness.events).anySatisfy(event ->
                assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.TOOL_CALL_STARTED));
        harness.assertSandboxReclaimed(SandboxTerminationReason.TASK_FAILED);
    }

    @Test
    void projectsFakeModelFailureAndReclaimsSandbox() throws Exception {
        ScenarioHarness harness = harness(Scenario.FAILURE);

        harness.submitAssignment();
        harness.awaitResult();

        assertThat(harness.projection).isEqualTo(
                new Projection(TaskState.FAILED, AttemptState.FAILED, LeaseState.RELEASED));
        assertThat(harness.results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isEqualTo("AgentScope execution failed");
        });
        harness.assertSandboxReclaimed(SandboxTerminationReason.TASK_FAILED);
    }

    @Test
    void projectsCancellationAndInterruptsTheFakeAgentSessionExactlyOnce() throws Exception {
        ScenarioHarness harness = harness(Scenario.CANCEL);

        harness.submitAssignment();
        assertThat(harness.runtime.cancel(harness.taskId)).isTrue();
        assertThat(harness.runtime.cancel(harness.taskId)).isFalse();
        harness.projection.cancelled();
        harness.sandbox.terminate(harness.sandboxHandle.providerSandboxId(),
                SandboxTerminationReason.TASK_CANCELLED);

        assertThat(harness.results).isEmpty();
        assertThat(harness.runtime.status(harness.taskId)).get()
                .extracting(RuntimeStatus::state).isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(harness.projection).isEqualTo(
                new Projection(TaskState.CANCELLED, AttemptState.CANCELLED, LeaseState.RELEASED));
        harness.assertSandboxReclaimed(SandboxTerminationReason.TASK_CANCELLED);
        assertThat(harness.model.interrupts()).isEqualTo(1);
    }

    @Test
    void projectsLeaseTimeoutAsCancellationAndReclaimsSandbox() throws Exception {
        ScenarioHarness harness = harness(Scenario.TIMEOUT);

        harness.submitAssignment();
        harness.clock.advance(Duration.ofSeconds(2));
        harness.runtime.expireLeases();
        harness.projection.timedOut();
        harness.sandbox.terminate(harness.sandboxHandle.providerSandboxId(),
                SandboxTerminationReason.LEASE_EXPIRED);

        assertThat(harness.results).isEmpty();
        assertThat(harness.runtime.status(harness.taskId)).get()
                .extracting(RuntimeStatus::state).isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(harness.projection).isEqualTo(
                new Projection(TaskState.TIMED_OUT, AttemptState.TIMED_OUT, LeaseState.EXPIRED));
        harness.assertSandboxReclaimed(SandboxTerminationReason.LEASE_EXPIRED);
        assertThat(harness.model.interrupts()).isEqualTo(1);
    }

    private ScenarioHarness harness(Scenario scenario) {
        ScenarioHarness harness = new ScenarioHarness(scenario);
        harnesses.add(harness);
        return harness;
    }

    private enum Scenario {
        SUCCESS,
        TOOL_REJECTED,
        FAILURE,
        CANCEL,
        TIMEOUT
    }

    private static final class ScenarioHarness implements AutoCloseable {
        private static final Instant START = Instant.parse("2026-08-25T00:00:00Z");

        private final Scenario scenario;
        private final UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        private final UUID attemptUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        private final String attemptId = attemptUuid.toString();
        private final String leaseId = "00000000-0000-0000-0000-000000000003";
        private final AdjustableClock clock = new AdjustableClock(START);
        private final FakeSandboxRuntime sandbox = new FakeSandboxRuntime();
        private final SandboxHandle sandboxHandle;
        private final List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        private final List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        private final List<AgentMessage> acceptedMessages = new CopyOnWriteArrayList<>();
        private final Projection projection = new Projection(TaskState.DRAFT, AttemptState.CREATED,
                LeaseState.ACTIVE);
        private final CountDownLatch resultLatch = new CountDownLatch(1);
        private final TrackingFakeModel model;
        private final AgentScopeRuntime runtime;
        private final GatewayRuntimeAdapter gateway;

        private ScenarioHarness(Scenario scenario) {
            this.scenario = scenario;
            this.model = new TrackingFakeModel(scenario);
            this.sandboxHandle = sandbox.provision(SandboxRequest.of(taskId, attemptUuid,
                    SandboxProfile.ISOLATED, Duration.ofMinutes(5), "agentscope-fake", START));
            this.runtime = new AgentScopeRuntime((task, context) -> createAgent(task), events::add);
            this.runtime.start(new AgentRuntimeContext("AGENTSCOPE", 1, clock,
                    result -> {
                        results.add(result);
                        projection.completed(result);
                        resultLatch.countDown();
                    },
                    Map.of("provider_id", "fake-agentscope", "model", "fake-model")));
            AgentChannelPort channel = message -> {
                if (message.hasTaskAccepted()) {
                    acceptedMessages.add(message);
                }
            };
            this.gateway = new GatewayRuntimeAdapter("agent-e2e", channel, runtime, clock);
        }

        private void submitAssignment() {
            projection.assigned();
            projection.running();
            var submission = gateway.acceptAssignment(assignment());
            assertThat(submission.accepted()).isTrue();
        }

        private void awaitResult() throws InterruptedException {
            assertThat(resultLatch.await(5, TimeUnit.SECONDS)).isTrue();
            SandboxTerminationReason reason = scenario == Scenario.SUCCESS
                    ? SandboxTerminationReason.TASK_COMPLETED
                    : scenario == Scenario.TOOL_REJECTED || scenario == Scenario.FAILURE
                            ? SandboxTerminationReason.TASK_FAILED : null;
            if (reason != null) {
                sandbox.terminate(sandboxHandle.providerSandboxId(), reason);
            }
        }

        private void assertSandboxReclaimed(SandboxTerminationReason expectedReason) {
            assertThat(sandbox.inspect(sandboxHandle.providerSandboxId())).isEqualTo(SandboxStatus.DESTROYED);
            assertThat(sandbox.terminateCalls()).isEqualTo(1);
            assertThat(sandbox.lastTerminationReason()).isEqualTo(expectedReason);
        }

        private TaskAssigned assignment() {
            Instant expiresAt = scenario == Scenario.TIMEOUT
                    ? START.plusSeconds(1) : START.plus(Duration.ofMinutes(5));
            return TaskAssigned.newBuilder()
                    .setMetadata(EventMetadata.newBuilder()
                            .setEventId("assignment-1")
                            .setAgentId("agent-e2e")
                            .setTaskId(taskId.toString())
                            .setAttemptId(attemptId)
                            .setLeaseId(leaseId)
                            .setExpectedVersion(1)
                            .build())
                    .setTaskType("summarize")
                    .setInputJson(com.google.protobuf.ByteString.copyFromUtf8(
                            "{\"text\":\"fixed input\"}"))
                    .setLeaseExpiresAt(timestamp(expiresAt))
                    .build();
        }

        private HarnessAgent createAgent(io.agentteams.runtime.RuntimeTask task) {
            try {
                Path workspace = Files.createTempDirectory("agentscope-e2e-");
                return HarnessAgent.builder()
                        .name("fake-agentscope-agent")
                        .agentId("agent-e2e")
                        .defaultSessionId(task.metadata().get("attemptId"))
                        .model(model)
                        .workspace(workspace)
                        .disableFilesystemTools()
                        .disableShellTool()
                        .disableSubagents()
                        .disableSessionPersistence()
                        .maxIters(2)
                        .build();
            } catch (Exception error) {
                throw new IllegalStateException("unable to create fake AgentScope agent", error);
            }
        }

        @Override
        public void close() {
            runtime.stop();
        }
    }

    private static final class TrackingFakeModel implements Model {
        private final Scenario scenario;
        private final AtomicInteger interrupts = new AtomicInteger();

        private TrackingFakeModel(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools, GenerateOptions options) {
            return switch (scenario) {
                case SUCCESS -> Flux.just(response(List.of(text("fixed fake answer")), 11, 7));
                case TOOL_REJECTED -> Flux.just(response(List.of(ToolUseBlock.builder()
                        .id("rejected-call")
                        .name("unapproved_tool")
                        .input(Map.of("secret", "must-not-run"))
                        .build()), 13, 5));
                case FAILURE -> Flux.error(new IllegalStateException("fake model failure"));
                case CANCEL, TIMEOUT -> Flux.<ChatResponse>never().doOnCancel(interrupts::incrementAndGet);
            };
        }

        @Override
        public String getModelName() {
            return "fake-model";
        }

        private int interrupts() {
            return interrupts.get();
        }
    }

    private static ChatResponse response(List<ContentBlock> content, int inputTokens, int outputTokens) {
        return ChatResponse.builder().id("fake-response").content(content)
                .usage(new ChatUsage(inputTokens, outputTokens, 0)).metadata(Map.of())
                .finishReason("stop").build();
    }

    private static TextBlock text(String value) {
        return TextBlock.builder().text(value).build();
    }

    private enum TaskState { DRAFT, ASSIGNED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

    private enum AttemptState { CREATED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }

    private enum LeaseState { ACTIVE, RELEASED, EXPIRED }

    private static final class Projection {
        private TaskState task;
        private AttemptState attempt;
        private LeaseState lease;

        private Projection(TaskState task, AttemptState attempt, LeaseState lease) {
            this.task = task;
            this.attempt = attempt;
            this.lease = lease;
        }

        private void assigned() {
            task = TaskState.ASSIGNED;
        }

        private void running() {
            task = TaskState.RUNNING;
            attempt = AttemptState.RUNNING;
        }

        private void completed(RuntimeResult result) {
            task = result.success() ? TaskState.SUCCEEDED : TaskState.FAILED;
            attempt = result.success() ? AttemptState.SUCCEEDED : AttemptState.FAILED;
            lease = LeaseState.RELEASED;
        }

        private void cancelled() {
            task = TaskState.CANCELLED;
            attempt = AttemptState.CANCELLED;
            lease = LeaseState.RELEASED;
        }

        private void timedOut() {
            task = TaskState.TIMED_OUT;
            attempt = AttemptState.TIMED_OUT;
            lease = LeaseState.EXPIRED;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Projection projection)) return false;
            return task == projection.task && attempt == projection.attempt && lease == projection.lease;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(task, attempt, lease);
        }

        @Override
        public String toString() {
            return "Projection[task=" + task + ", attempt=" + attempt + ", lease=" + lease + "]";
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant current;

        private AdjustableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static com.google.protobuf.Timestamp timestamp(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder().setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano()).build();
    }
}
