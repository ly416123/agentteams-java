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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    @Test
    void stableBucketCombinesTenantProjectTeamAgentAndTask() throws Exception {
        RecordingRuntime qwen = new RecordingRuntime();
        RecordingRuntime agentScope = new RecordingRuntime();
        WorkerRuntimeRouter router = new WorkerRuntimeRouter(qwen, agentScope,
                new AgentScopeRolloutPolicy("QWENPAW", true, 50,
                        Set.of(), Set.of(), Set.of()));
        router.start(context());

        RuntimeTask first = null;
        RuntimeTask second = null;
        boolean firstAgentScope = false;
        for (int index = 0; index < 100 && second == null; index++) {
            RuntimeTask candidate = new RuntimeTask(
                    UUID.nameUUIDFromBytes(("task-" + index).getBytes(StandardCharsets.UTF_8)),
                    "chat", "{}", Map.of("tenantId", "tenant-" + index,
                            "projectId", "project-" + index, "teamId", "team-" + index,
                            "agentId", "agent-a"));
            boolean candidateAgentScope = bucket(candidate) < 50;
            if (first == null) {
                first = candidate;
                firstAgentScope = candidateAgentScope;
            } else if (candidateAgentScope != firstAgentScope) {
                second = candidate;
            }
        }
        assertThat(second).as("test data must contain both bucket outcomes").isNotNull();

        router.submit(first);
        router.submit(second);

        if (firstAgentScope) {
            assertThat(agentScope.submitted()).contains(first.id());
            assertThat(qwen.submitted()).contains(second.id());
        } else {
            assertThat(qwen.submitted()).contains(first.id());
            assertThat(agentScope.submitted()).contains(second.id());
        }
    }

    private static int bucket(RuntimeTask task) throws Exception {
        StringBuilder value = new StringBuilder("agentteams-routing-v1|");
        for (String field : new String[] {"tenantId", "projectId", "teamId", "agentId"}) {
            String part = task.metadata().get(field);
            value.append(part.length()).append(':').append(part).append('|');
        }
        String taskId = task.id().toString();
        value.append(taskId.length()).append(':').append(taskId);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toString().getBytes(StandardCharsets.UTF_8));
        return ((((digest[0] & 0xff) << 8) | (digest[1] & 0xff)) % 100);
    }

    private static AgentRuntimeContext context() {
        return new AgentRuntimeContext("worker", 2, CLOCK, ignored -> { }, Map.of());
    }

    private static RuntimeTask task() {
        return new RuntimeTask(TASK_ID, "chat", "{}", Map.of(
                "agentId", "agent-a", "teamId", "team-a", "projectId", "project-a",
                "tenantId", "tenant-a"));
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
