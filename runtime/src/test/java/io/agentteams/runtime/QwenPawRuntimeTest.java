package io.agentteams.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QwenPawRuntimeTest {
    @Test
    void delegatesProcessLifecycleAndTaskSubmissionThroughExternalPort() {
        RecordingProcessPort port = new RecordingProcessPort();
        QwenPawRuntime runtime = new QwenPawRuntime(port);
        runtime.start(new AgentRuntimeContext(
                "qwenpaw-test", 2, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                result -> { }, Map.of("command", "qwenpaw")));
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "chat", "{}", Map.of());

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(port.started()).isTrue();
        assertThat(port.submitted()).containsExactly(task);
        assertThat(runtime.cancel(task.id())).isTrue();
        assertThat(port.cancelled()).containsExactly(task.id());

        runtime.stop();
        assertThat(port.stopped()).isTrue();
    }

    @Test
    void admitsBeforeProcessSubmissionAndReleasesOnTerminalResult() {
        RecordingProcessPort port = new RecordingProcessPort();
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        RuntimeModelCallAdmission admission = request -> {
            acquired.incrementAndGet();
            return RuntimeModelCallLease.idempotent(released::incrementAndGet);
        };
        java.util.List<RuntimeResult> results = new java.util.ArrayList<>();
        QwenPawRuntime runtime = new QwenPawRuntime(port, admission);
        runtime.start(new AgentRuntimeContext("qwenpaw-test", 2,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), results::add,
                Map.of("provider_id", "deepseek", "model", "deepseek-chat", "modelMaxTokens", "256")));
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "chat", "{}", Map.of());

        assertThat(runtime.submit(task).accepted()).isTrue();
        assertThat(acquired).hasValue(1);
        assertThat(released).hasValue(0);
        port.emit(RuntimeResult.success(task.id(), "ok", Instant.EPOCH));

        assertThat(results).singleElement().extracting(RuntimeResult::output).isEqualTo("ok");
        assertThat(released).hasValue(1);
        runtime.stop();
        assertThat(released).hasValue(1);
    }

    @Test
    void carriesConfiguredTenantAndProjectScopeIntoAdmissionRequest() {
        RecordingProcessPort port = new RecordingProcessPort();
        AtomicReference<RuntimeModelCallAdmissionRequest> request = new AtomicReference<>();
        QwenPawRuntime runtime = new QwenPawRuntime(port, value -> {
            request.set(value);
            return RuntimeModelCallLease.noop();
        });
        runtime.start(new AgentRuntimeContext("qwenpaw-test", 1,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), result -> { },
                Map.of("provider_id", "qwen", "model", "qwen-plus", "modelMaxTokens", "321",
                        "tenant_id", "tenant-a", "project_id", "project-a")));

        runtime.submit(new RuntimeTask(UUID.randomUUID(), "chat", "{}", Map.of()));

        assertThat(request).hasValueSatisfying(value -> {
            assertThat(value.tenantId()).isEqualTo("tenant-a");
            assertThat(value.projectId()).isEqualTo("project-a");
            assertThat(value.maxTokens()).isEqualTo(321);
        });
        runtime.stop();
    }

    @Test
    void admissionRejectionDoesNotSubmitToProvider() {
        RecordingProcessPort port = new RecordingProcessPort();
        QwenPawRuntime runtime = new QwenPawRuntime(port,
                request -> { throw new RuntimeModelCallAdmissionRejectedException("quota"); });
        runtime.start(new AgentRuntimeContext("qwenpaw-test", 1,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), result -> { }, Map.of()));
        RuntimeTask task = new RuntimeTask(UUID.randomUUID(), "chat", "{}", Map.of());

        assertThat(runtime.submit(task)).isEqualTo(RuntimeSubmission.rejected("quota"));
        assertThat(port.submitted()).isEmpty();
        assertThat(runtime.status(task.id())).get().extracting(RuntimeStatus::state)
                .isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runtime.snapshot().running()).isZero();
    }

    private static final class RecordingProcessPort implements QwenPawProcessPort {
        private boolean started;
        private boolean stopped;
        private final java.util.List<RuntimeTask> submitted = new java.util.ArrayList<>();
        private final java.util.List<UUID> cancelled = new java.util.ArrayList<>();

        @Override
        public void start(AgentRuntimeContext context, RuntimeResultSink resultSink) {
            started = true;
            this.resultSink = resultSink;
        }

        @Override
        public void submit(RuntimeTask task) {
            submitted.add(task);
        }

        @Override
        public void cancel(UUID taskId) {
            cancelled.add(taskId);
        }

        @Override
        public void stop() {
            stopped = true;
        }

        void emit(RuntimeResult result) {
            resultSink.accept(result);
        }

        boolean started() { return started; }
        boolean stopped() { return stopped; }
        java.util.List<RuntimeTask> submitted() { return submitted; }
        java.util.List<UUID> cancelled() { return cancelled; }

        private RuntimeResultSink resultSink;
    }
}
