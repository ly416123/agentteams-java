package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.runtime.AgentRuntime;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.AgentScopeRolloutPolicy;
import io.agentteams.runtime.CompletionStatus;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeSnapshot;
import io.agentteams.runtime.RuntimeStatus;
import io.agentteams.runtime.RuntimeSubmission;
import io.agentteams.runtime.RuntimeTask;
import io.agentteams.runtime.RuntimeTaskState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerRuntimeRouterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");

    @Test
    void preservesTheOriginalOwnerWhenRolloutPolicyChanges() {
        RecordingRuntime qwen = new RecordingRuntime();
        RecordingRuntime agentScope = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, agentScope,
                new AgentScopeRolloutPolicy("QWENPAW", true, 0,
                        Set.of(), Set.of(), Set.of()));
        router.start(context());

        assertThat(router.submit(task())).isEqualTo(RuntimeSubmission.acceptedSubmission());
        router.updatePolicy(new AgentScopeRolloutPolicy("QWENPAW", true, 100,
                Set.of(), Set.of(), Set.of()));

        assertThat(router.cancel(TASK_ID)).isTrue();
        assertThat(qwen.cancelled()).containsExactly(TASK_ID);
        assertThat(agentScope.cancelled()).isEmpty();
    }

    @Test
    void routesResultCompletionToTheOwnerSelectedAtSubmit() {
        RecordingRuntime qwen = new RecordingRuntime();
        RecordingRuntime agentScope = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, agentScope,
                new AgentScopeRolloutPolicy("QWENPAW", true, 0,
                        Set.of(), Set.of(), Set.of()));
        router.start(context());

        router.submit(task());
        assertThat(router.complete(RuntimeResult.success(TASK_ID, "ok", CLOCK.instant())))
                .isEqualTo(CompletionStatus.COMPLETED);
        assertThat(qwen.completed()).containsExactly(TASK_ID);
        assertThat(agentScope.completed()).isEmpty();
    }

    @Test
    void stopsTheOriginalOwnerWhenTheRouterStops() {
        RecordingRuntime qwen = new RecordingRuntime();
        RecordingRuntime agentScope = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, agentScope,
                new AgentScopeRolloutPolicy("QWENPAW", true, 0,
                        Set.of(), Set.of(), Set.of()));
        router.start(context());

        router.submit(task());
        router.stop();

        assertThat(qwen.stopCount()).isEqualTo(1);
        assertThat(agentScope.stopCount()).isEqualTo(1);
    }

    @Test
    void returnsRuntimeUnavailableBeforeAcceptingWhenAgentScopeIsSelectedButUnavailable() {
        RecordingRuntime qwen = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, null,
                new AgentScopeRolloutPolicy("QWENPAW", true, 0,
                        Set.of("agent-a"), Set.of(), Set.of()));
        router.start(context());

        assertThat(router.submit(task())).isEqualTo(RuntimeSubmission.rejected("RUNTIME_UNAVAILABLE"));
        assertThat(qwen.submitted()).isEmpty();
    }

    @Test
    void missingStableScopeFailsClosedToQwenPaw() {
        RecordingRuntime qwen = new RecordingRuntime();
        RecordingRuntime agentScope = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, agentScope,
                new AgentScopeRolloutPolicy("AGENTSCOPE", true, 100,
                        Set.of(), Set.of(), Set.of()));
        router.start(context());

        assertThat(router.submit(new RuntimeTask(TASK_ID, "chat", "{}", Map.of(
                "agentId", "agent-a", "teamId", "team-a"))))
                .isEqualTo(RuntimeSubmission.acceptedSubmission());
        assertThat(qwen.submitted()).containsExactly(TASK_ID);
        assertThat(agentScope.submitted()).isEmpty();
    }

    private static AgentRuntimeContext context() {
        return new AgentRuntimeContext("worker", 2, CLOCK, ignored -> { }, Map.of());
    }

    private static RuntimeTask task() {
        return new RuntimeTask(TASK_ID, "chat", "{}", Map.of(
                "agentId", "agent-a", "teamId", "team-a", "tenantId", "tenant-a"));
    }

    private static final class RecordingRuntime implements AgentRuntime {
        private final List<UUID> submitted = new ArrayList<>();
        private final List<UUID> cancelled = new ArrayList<>();
        private final List<UUID> completed = new ArrayList<>();
        private int stopCount;

        @Override
        public void start(AgentRuntimeContext context) { }

        @Override
        public RuntimeSubmission submit(RuntimeTask task) {
            submitted.add(task.id());
            return RuntimeSubmission.acceptedSubmission();
        }

        @Override
        public CompletionStatus complete(RuntimeResult result) {
            completed.add(result.taskId());
            return CompletionStatus.COMPLETED;
        }

        @Override
        public boolean cancel(UUID taskId) {
            cancelled.add(taskId);
            return true;
        }

        @Override
        public Optional<RuntimeStatus> status(UUID taskId) { return Optional.empty(); }

        @Override
        public RuntimeSnapshot snapshot() { return new RuntimeSnapshot(0, submitted.size()); }

        @Override
        public void stop() { stopCount++; }

        List<UUID> submitted() { return submitted; }
        List<UUID> cancelled() { return cancelled; }
        List<UUID> completed() { return completed; }
        int stopCount() { return stopCount; }
    }
}
