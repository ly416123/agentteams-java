package io.agentteams.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void startsAcceptsTasksUpToConcurrencyAndTracksState() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(context(1));
        RuntimeTask first = task("first");
        RuntimeTask second = task("second");

        assertThat(runtime.submit(first).accepted()).isTrue();
        assertThat(runtime.submit(second))
                .extracting(RuntimeSubmission::accepted, RuntimeSubmission::state)
                .containsExactly(false, RuntimeTaskState.REJECTED);
        assertThat(runtime.status(first.id())).hasValueSatisfying(status -> {
            assertThat(status.state()).isEqualTo(RuntimeTaskState.RUNNING);
        });
        assertThat(runtime.snapshot().running()).isEqualTo(1);
    }

    @Test
    void completesTaskOnlyOnceAndPublishesOnlyTheFirstResult() {
        RecordingResultSink sink = new RecordingResultSink();
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(context(1, sink));
        RuntimeTask task = task("deduplicated");
        RuntimeResult result = RuntimeResult.success(task.id(), "done", NOW);

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(runtime.complete(result)).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(runtime.complete(result)).isEqualTo(CompletionStatus.DUPLICATE);
        assertThat(sink.results()).containsExactly(result);
        assertThat(runtime.status(task.id())).hasValueSatisfying(status -> {
            assertThat(status.state()).isEqualTo(RuntimeTaskState.SUCCEEDED);
            assertThat(status.result()).contains(result);
        });
    }

    @Test
    void rejectsCompletionForUnknownTaskAndDoesNotChangeCapacity() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(context(1));

        assertThat(runtime.complete(RuntimeResult.failure(UUID.randomUUID(), "missing", NOW)))
                .isEqualTo(CompletionStatus.UNKNOWN_TASK);
        assertThat(runtime.snapshot().running()).isZero();
    }

    @Test
    void rejectsOperationsBeforeStartAndAfterStop() {
        FakeRuntime runtime = new FakeRuntime();
        RuntimeTask task = task("lifecycle");

        assertThatThrownBy(() -> runtime.submit(task))
                .isInstanceOf(IllegalStateException.class);
        runtime.start(context(1));
        runtime.stop();
        assertThatThrownBy(() -> runtime.submit(task))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationFreesConcurrencyAndIsTerminal() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(context(1));
        RuntimeTask task = task("cancel");

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(runtime.cancel(task.id())).isTrue();
        assertThat(runtime.cancel(task.id())).isFalse();
        assertThat(runtime.status(task.id())).hasValueSatisfying(status -> {
            assertThat(status.state()).isEqualTo(RuntimeTaskState.CANCELLED);
        });
        assertThat(runtime.snapshot().running()).isZero();
    }

    @Test
    void appliesImmutableConfigurationWithoutChangingRunningTaskState() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.start(context(1));

        runtime.applyConfig(new RuntimeConfigSnapshot(2, "sha-2", java.util.Map.of("model", "deepseek")));

        assertThat(runtime.configuration()).hasValueSatisfying(snapshot -> assertThat(snapshot.version()).isEqualTo(2));
        assertThat(runtime.snapshot().running()).isZero();
    }

    private static AgentRuntimeContext context(int maxConcurrency) {
        return context(maxConcurrency, result -> { });
    }

    private static AgentRuntimeContext context(int maxConcurrency, RuntimeResultSink sink) {
        return new AgentRuntimeContext(
                "fake-test",
                maxConcurrency,
                Clock.fixed(NOW, ZoneOffset.UTC),
                sink,
                java.util.Map.of());
    }

    private static RuntimeTask task(String type) {
        return new RuntimeTask(UUID.randomUUID(), type, "{}", java.util.Map.of("source", "test"));
    }

    private static final class RecordingResultSink implements RuntimeResultSink {
        private final java.util.List<RuntimeResult> results = new java.util.ArrayList<>();

        @Override
        public void accept(RuntimeResult result) {
            results.add(result);
        }

        java.util.List<RuntimeResult> results() {
            return results;
        }
    }
}
