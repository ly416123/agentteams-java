package io.agentteams.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

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

    private static final class RecordingProcessPort implements QwenPawProcessPort {
        private boolean started;
        private boolean stopped;
        private final java.util.List<RuntimeTask> submitted = new java.util.ArrayList<>();
        private final java.util.List<UUID> cancelled = new java.util.ArrayList<>();

        @Override
        public void start(AgentRuntimeContext context, RuntimeResultSink resultSink) {
            started = true;
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

        boolean started() { return started; }
        boolean stopped() { return stopped; }
        java.util.List<RuntimeTask> submitted() { return submitted; }
        java.util.List<UUID> cancelled() { return cancelled; }
    }
}
