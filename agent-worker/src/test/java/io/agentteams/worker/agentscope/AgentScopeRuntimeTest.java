package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.runtime.RuntimeTaskState;
import io.agentteams.runtime.RuntimeSubmission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AgentScopeRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    private final List<HarnessAgent> agents = new ArrayList<>();
    private AgentScopeRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.stop();
        }
        agents.forEach(HarnessAgent::close);
    }

    @Test
    void streamsAgentScopeEventsAndCompletesOnlyOnAgentEnd() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("safe answer"), events::add);
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));
        RuntimeTask task = task("attempt-1", "lease-1", "corr-1");

        assertThat(runtime.submit(task)).isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).output()).isEqualTo("safe answer");
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.SUCCEEDED);
        assertThat(events).extracting(AgentScopeExecutionEvent::kind)
                .contains(AgentScopeExecutionEvent.Kind.AGENT_STARTED,
                        AgentScopeExecutionEvent.Kind.AGENT_RESULT,
                        AgentScopeExecutionEvent.Kind.AGENT_ENDED);
        assertThat(events).filteredOn(AgentScopeExecutionEvent::terminal).hasSize(1);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.taskId()).isEqualTo(task.id().toString());
            assertThat(event.attemptId()).isEqualTo("attempt-1");
            assertThat(event.leaseId()).isEqualTo("lease-1");
            assertThat(event.correlationId()).isEqualTo("corr-1");
            assertThat(event.runtime()).isEqualTo("agentscope-test");
        });
    }

    @Test
    void agentResultIsOnlyASafeFinalTextCandidateAndDoesNotCompleteEarly() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        runtime = newRuntime(new DeterministicModel("Authorization: Bearer secret-token"), event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            completed.countDown();
        }));

        assertThat(runtime.submit(task("attempt-2", "lease-2", "corr-2")).accepted()).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).output()).doesNotContain("secret-token");
        assertThat(results.get(0).output()).contains("[REDACTED]");
    }

    @Test
    void factoryFailurePublishesOneFailedRuntimeResult() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        runtime = new AgentScopeRuntime((task, context) -> {
            throw new IllegalStateException("factory secret must not be returned");
        }, event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            failed.countDown();
        }));

        RuntimeTask task = task("attempt-factory", "lease-factory", "corr-factory");
        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).doesNotContain("factory secret");
        });
    }

    @Test
    void fluxFailurePublishesFailedRuntimeResult() throws Exception {
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        runtime = newRuntime(new FailingModel(), event -> { });
        runtime.start(context(1, result -> {
            results.add(result);
            failed.countDown();
        }));

        assertThat(runtime.submit(task("attempt-error", "lease-error", "corr-error")).accepted()).isTrue();
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isFalse();
            assertThat(result.output()).isEqualTo("AgentScope execution failed");
        });
    }

    @Test
    void duplicateSubmitIsRejectedAndConcurrencyLimitIsPreserved() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask first = task("attempt-running", "lease-running", "corr-running");
        RuntimeTask second = task("attempt-second", "lease-second", "corr-second");

        assertThat(runtime.submit(first).accepted()).isTrue();
        assertThat(runtime.submit(first)).isEqualTo(RuntimeSubmission.rejected("task already exists"));
        assertThat(runtime.submit(second)).isEqualTo(RuntimeSubmission.rejected("maximum concurrency reached"));
        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(1, 1));
    }

    @Test
    void cancelInterruptsAgentDisposesStreamAndClearsExecution() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask task = task("attempt-cancel", "lease-cancel", "corr-cancel");

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(runtime.cancel(task.id())).isTrue();
        assertThat(runtime.cancel(task.id())).isFalse();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runtime.snapshot().running()).isZero();
    }

    @Test
    void stopInterruptsAndClosesAllActiveSessions() {
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(2, result -> { }));
        assertThat(runtime.submit(task("attempt-stop-1", "lease-stop-1", "corr-stop-1")).accepted()).isTrue();
        assertThat(runtime.submit(task("attempt-stop-2", "lease-stop-2", "corr-stop-2")).accepted()).isTrue();

        runtime.stop();

        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(0, 0));
        assertThat(runtime.status(UUID.randomUUID())).isEmpty();
    }

    @Test
    void requiresRuntimeToBeStartedBeforeSubmission() {
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });

        assertThatThrownBy(() -> runtime.submit(task("attempt-not-started", "lease-not-started", "corr-not-started")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime is not started");
    }

    private AgentScopeRuntime newRuntime(Model model, Consumer<AgentScopeExecutionEvent> eventSink) {
        return new AgentScopeRuntime((task, context) -> {
            Path workspace;
            try {
                workspace = Files.createTempDirectory("agentscope-runtime-test-");
            } catch (java.io.IOException error) {
                throw new IllegalStateException("unable to create test workspace", error);
            }
            HarnessAgent agent = HarnessAgent.builder()
                    .name("test-agent")
                    .agentId("agent-" + task.id())
                    .defaultSessionId(task.metadata().get("attemptId"))
                    .model(model)
                    .workspace(workspace)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableSubagents()
                    .disableSessionPersistence()
                    .maxIters(2)
                    .build();
            agents.add(agent);
            return agent;
        }, eventSink);
    }

    private static AgentRuntimeContext context(int maxConcurrency, Consumer<RuntimeResult> sink) {
        return new AgentRuntimeContext("agentscope-test", maxConcurrency,
                Clock.fixed(NOW, ZoneOffset.UTC), sink::accept, Map.of());
    }

    private static RuntimeTask task(String attemptId, String leaseId, String correlationId) {
        return new RuntimeTask(UUID.randomUUID(), "chat", "hello", Map.of(
                "attemptId", attemptId,
                "leaseId", leaseId,
                "correlationId", correlationId,
                "agentId", "worker-test"));
    }

    private static final class DeterministicModel implements Model {
        private final String response;

        private DeterministicModel(String response) {
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            ContentBlock text = TextBlock.builder().text(response).build();
            return Flux.just(ChatResponse.builder().id("fake-response")
                    .content(List.of(text)).usage(new ChatUsage(3, 2, 0.01)).finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "fake-agentscope-model";
        }
    }

    private static final class FailingModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.error(new IllegalStateException("model secret must not leak"));
        }

        @Override
        public String getModelName() {
            return "failing-agentscope-model";
        }
    }

    private static final class BlockingModel implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<io.agentscope.core.model.ToolSchema> tools,
                GenerateOptions options) {
            return Flux.never();
        }

        @Override
        public String getModelName() {
            return "blocking-agentscope-model";
        }
    }

}
