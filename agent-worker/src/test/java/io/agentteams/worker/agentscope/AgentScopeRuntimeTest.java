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
import io.agentscope.core.message.UserMessage;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
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

    @Test
    void stopWaitsForAnInFlightSubmitAndLeavesNoOrphanExecution() throws Exception {
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicReference<RuntimeSubmission> submission = new AtomicReference<>();
        runtime = new AgentScopeRuntime((task, context) -> {
            factoryEntered.countDown();
            await(releaseFactory);
            return newHarness(new DeterministicModel("race-safe"), task);
        }, event -> { });
        runtime.start(context(1, result -> { }));
        RuntimeTask task = task("attempt-race", "lease-race", "corr-race");

        Thread submitThread = new Thread(() -> submission.set(runtime.submit(task)), "agentscope-submit-test");
        submitThread.start();
        assertThat(factoryEntered.await(2, TimeUnit.SECONDS)).isTrue();

        Thread stopThread = new Thread(() -> {
            runtime.stop();
            stopReturned.countDown();
        }, "agentscope-stop-test");
        stopThread.start();

        assertThat(stopReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        releaseFactory.countDown();
        submitThread.join(2000);
        stopThread.join(2000);

        assertThat(submission.get()).isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(stopReturned.getCount()).isZero();
        assertThat(runtime.snapshot()).isEqualTo(new RuntimeSnapshot(0, 0));
    }

    @Test
    void submitAfterStopFailsClearly() {
        runtime = newRuntime(new DeterministicModel("unused"), event -> { });
        runtime.start(context(1, result -> { }));
        runtime.stop();

        assertThatThrownBy(() -> runtime.submit(task("attempt-stopped", "lease-stopped", "corr-stopped")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runtime is not started");
    }

    @Test
    void installsDisposableAtomicallyAndDisposesItAfterSynchronousClose() {
        RuntimeTask task = task("attempt-disposable", "lease-disposable", "corr-disposable");
        HarnessAgent agent = newHarness(new BlockingModel(), task);
        AgentScopeRuntime.Execution execution = new AgentScopeRuntime.Execution(task, agent,
                translator(task), "attempt-disposable", "lease-disposable", 1);
        RecordingDisposable disposable = new RecordingDisposable(false);

        execution.close();

        assertThat(execution.installDisposable(disposable)).isFalse();
        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void cleanupContinuesWhenDisposableDisposalFailsAndDoesNotLeakErrorDetails() {
        RuntimeTask task = task("attempt-cleanup", "lease-cleanup", "corr-cleanup");
        HarnessAgent agent = newHarness(new BlockingModel(), task);
        AgentScopeEventTranslator translator = translator(task);
        AgentScopeRuntime.Execution execution = new AgentScopeRuntime.Execution(task, agent, translator,
                "attempt-cleanup", "lease-cleanup", 1);
        RecordingDisposable disposable = new RecordingDisposable(true);
        assertThat(execution.installDisposable(disposable)).isTrue();

        execution.close();

        assertThat(disposable.disposed).isTrue();
        AgentScopeExecutionEvent afterClose = translator.translate(
                new io.agentscope.core.event.AgentStartEvent("late-cleanup", "2026-08-25T00:00:00Z",
                        "session", "reply", "agent", "assistant")
                        .withMetadataEntry("attemptId", "attempt-cleanup"));
        assertThat(afterClose.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
        assertThat(afterClose.safeMessage()).isEqualTo("translator closed");
    }

    @Test
    void rejectsLateEventsAfterAgentEndBeforeEventSink() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-late", "lease-late", "corr-late");
        assertThat(runtime.submit(task).accepted()).isTrue();

        Object execution = activeExecution(task.id());
        invokeOnEvent(execution, new io.agentscope.core.event.AgentEndEvent(
                "end-late", "2026-08-25T00:00:00Z", "reply")
                .withMetadataEntry("attemptId", "attempt-late"));
        int eventCountAfterEnd = events.size();
        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "after-end", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-late"));

        assertThat(events).hasSize(eventCountAfterEnd);
        assertThat(results).hasSize(1);
    }

    @Test
    void resultSinkFailureStillCleansExecutionAndKeepsTerminalCommitted() throws Exception {
        AtomicReference<Integer> sinkCalls = new AtomicReference<>(0);
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, result -> {
            sinkCalls.updateAndGet(calls -> calls + 1);
            throw new IllegalStateException("result sink secret must not leak");
        }));
        RuntimeTask task = task("attempt-sink-error", "lease-sink-error", "corr-sink-error");
        assertThat(runtime.submit(task).accepted()).isTrue();

        AgentScopeRuntime.Execution execution = (AgentScopeRuntime.Execution) activeExecution(task.id());

        assertThatThrownBy(() -> runtime.complete(RuntimeResult.success(task.id(), "external", NOW)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sinkCalls).hasValue(1);
        assertThat(activeExecution(task.id())).isNull();
        assertThat(executionClosed(execution)).isTrue();
        assertThat(executionDisposable(execution)).isNull();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.SUCCEEDED);
        assertThat(runtime.snapshot().running()).isZero();
        assertThat(runtime.complete(RuntimeResult.success(task.id(), "duplicate", NOW)))
                .isEqualTo(io.agentteams.runtime.CompletionStatus.DUPLICATE);

        int eventsBeforeLateEvent = events.size();
        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "late-after-sink-error", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-sink-error"));
        assertThat(events).hasSize(eventsBeforeLateEvent);
    }

    @Test
    void staleAttemptEventIsRecordedButDoesNotFailCurrentAttempt() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-current", "lease-current", "corr-current");
        assertThat(runtime.submit(task).accepted()).isTrue();
        Object execution = activeExecution(task.id());

        invokeOnEvent(execution, new io.agentscope.core.event.AgentStartEvent(
                "stale-attempt", "2026-08-25T00:00:00Z", "session", "reply", "agent", "assistant")
                .withMetadataEntry("attemptId", "attempt-old"));

        assertThat(events).last().satisfies(event -> {
            assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
            assertThat(event.safeMessage()).isEqualTo("stale execution context");
        });
        assertThat(results).isEmpty();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.RUNNING);

        invokeOnEvent(execution, new io.agentscope.core.event.ExceedMaxItersEvent(
                "real-error", "2026-08-25T00:00:00Z", "reply", 2, 2)
                .withMetadataEntry("attemptId", "attempt-current"));

        assertThat(results).singleElement().satisfies(result -> assertThat(result.success()).isFalse());
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.FAILED);
    }

    @Test
    void staleAttemptAgentResultCannotPolluteCurrentResultCandidate() throws Exception {
        List<AgentScopeExecutionEvent> events = new CopyOnWriteArrayList<>();
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), events::add);
        runtime.start(context(1, results::add));
        RuntimeTask task = task("attempt-current", "lease-current", "corr-current");
        assertThat(runtime.submit(task).accepted()).isTrue();
        Object execution = activeExecution(task.id());

        invokeOnEvent(execution, new io.agentscope.core.event.AgentResultEvent(
                "stale-result", "2026-08-25T00:00:00Z", new UserMessage("old attempt result"))
                .withMetadata(Map.of("attemptId", "attempt-old", "leaseId", "lease-old")));
        invokeOnEvent(execution, new io.agentscope.core.event.AgentEndEvent(
                "current-end", "2026-08-25T00:00:00Z", "reply")
                .withMetadata(Map.of("attemptId", "attempt-current", "leaseId", "lease-current")));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEmpty();
        });
        assertThat(events).filteredOn(event -> event.eventId().equals("stale-result"))
                .singleElement().satisfies(event -> {
                    assertThat(event.kind()).isEqualTo(AgentScopeExecutionEvent.Kind.STALE);
                    assertThat(event.duplicate()).isFalse();
                });
    }

    @Test
    void expiredLeaseClosesExecutionWithoutSubmittingResult() throws Exception {
        AdjustableClock clock = new AdjustableClock(NOW);
        List<RuntimeResult> results = new CopyOnWriteArrayList<>();
        runtime = newRuntime(new BlockingModel(), event -> { });
        runtime.start(context(1, clock, results::add));
        RuntimeTask task = task("attempt-expiry", "lease-expiry", "corr-expiry",
                NOW.plusSeconds(30).toString());
        assertThat(runtime.submit(task).accepted()).isTrue();
        AgentScopeRuntime.Execution execution = (AgentScopeRuntime.Execution) activeExecution(task.id());

        clock.set(NOW.plusSeconds(30));
        runtime.expireLeases();

        assertThat(results).isEmpty();
        assertThat(executionClosed(execution)).isTrue();
        assertThat(executionDisposable(execution)).isNull();
        assertThat(activeExecution(task.id())).isNull();
        assertThat(runtime.status(task.id())).get().extracting(status -> status.state())
                .isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runtime.snapshot().running()).isZero();
        assertThat(runtime.complete(RuntimeResult.success(task.id(), "late", clock.instant())))
                .isEqualTo(io.agentteams.runtime.CompletionStatus.DUPLICATE);
    }

    private AgentScopeRuntime newRuntime(Model model, Consumer<AgentScopeExecutionEvent> eventSink) {
        return new AgentScopeRuntime((task, context) -> {
            Path workspace;
            try {
                workspace = Files.createTempDirectory("agentscope-runtime-test-");
            } catch (java.io.IOException error) {
                throw new IllegalStateException("unable to create test workspace", error);
            }
            HarnessAgent agent = newHarness(model, task, workspace);
            agents.add(agent);
            return agent;
        }, eventSink);
    }

    private HarnessAgent newHarness(Model model, RuntimeTask task) {
        try {
            return newHarness(model, task, Files.createTempDirectory("agentscope-runtime-test-"));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("unable to create test workspace", error);
        }
    }

    private static HarnessAgent newHarness(Model model, RuntimeTask task, Path workspace) {
        return HarnessAgent.builder()
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
    }

    private AgentScopeEventTranslator translator(RuntimeTask task) {
        return new AgentScopeEventTranslator(task.id().toString(), task.metadata().get("attemptId"),
                task.metadata().get("leaseId"), task.metadata().get("correlationId"), "agentscope-test");
    }

    private Object activeExecution(UUID taskId) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.class.getDeclaredField("executions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> executions = (Map<UUID, Object>) field.get(runtime);
        return executions.get(taskId);
    }

    private void invokeOnEvent(Object execution, io.agentscope.core.event.AgentEvent event) throws Exception {
        java.lang.reflect.Method method = AgentScopeRuntime.class.getDeclaredMethod(
                "onEvent", execution.getClass(), io.agentscope.core.event.AgentEvent.class);
        method.setAccessible(true);
        method.invoke(runtime, execution, event);
    }

    private static boolean executionClosed(AgentScopeRuntime.Execution execution) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.Execution.class.getDeclaredField("closed");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(execution)).get();
    }

    private static Object executionDisposable(AgentScopeRuntime.Execution execution) throws Exception {
        java.lang.reflect.Field field = AgentScopeRuntime.Execution.class.getDeclaredField("disposable");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicReference<?>) field.get(execution)).get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", error);
        }
    }

    private static final class RecordingDisposable implements Disposable {
        private final boolean failOnDispose;
        private boolean disposed;

        private RecordingDisposable(boolean failOnDispose) {
            this.failOnDispose = failOnDispose;
        }

        @Override
        public void dispose() {
            disposed = true;
            if (failOnDispose) {
                throw new IllegalStateException("disposable secret must not leak");
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private static AgentRuntimeContext context(int maxConcurrency, Consumer<RuntimeResult> sink) {
        return context(maxConcurrency, Clock.fixed(NOW, ZoneOffset.UTC), sink);
    }

    private static AgentRuntimeContext context(int maxConcurrency, Clock clock,
            Consumer<RuntimeResult> sink) {
        return new AgentRuntimeContext("agentscope-test", maxConcurrency,
                clock, sink::accept, Map.of());
    }

    private static RuntimeTask task(String attemptId, String leaseId, String correlationId) {
        return task(attemptId, leaseId, correlationId, null);
    }

    private static RuntimeTask task(String attemptId, String leaseId, String correlationId,
            String leaseExpiresAt) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of(
                "attemptId", attemptId,
                "leaseId", leaseId,
                "correlationId", correlationId,
                "agentId", "worker-test"));
        if (leaseExpiresAt != null) {
            metadata.put("leaseExpiresAt", leaseExpiresAt);
        }
        return new RuntimeTask(UUID.randomUUID(), "chat", "hello", metadata);
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        private AdjustableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        private void set(Instant instant) {
            current.set(instant);
        }
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
