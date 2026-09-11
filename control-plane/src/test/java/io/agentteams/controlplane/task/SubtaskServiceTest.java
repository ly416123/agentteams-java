package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubtaskServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private final TaskTreeRepository tree = mock(TaskTreeRepository.class);
    private final TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
    private final RecordingEventRepository events = new RecordingEventRepository();
    private final UUID taskId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private SubtaskDelegationService delegation;
    private SubtaskService service;

    @BeforeEach
    void setUp() {
        when(runs.contextForTask(taskId)).thenReturn(Optional.of(CONTEXT));
        when(runs.latestRunId(taskId)).thenReturn(Optional.of(runId));
        when(runs.nextSequence(runId)).thenReturn(7L, 8L, 9L, 10L, 11L);
        when(tree.find(CONTEXT, runId)).thenReturn(List.of());
        // G03：真实任务行的创建/取消由 SubtaskDelegationService 负责；
        // 本测试聚焦投影层，用真实语义的 stub 模拟 plan 结果（keep = 清单内）。
        delegation = mock(SubtaskDelegationService.class);
        when(delegation.plan(any(UUID.class), any(List.class))).thenAnswer(invocation -> {
            List<SubtaskService.SubtaskSpec> specs = invocation.getArgument(1);
            List<SubtaskDelegationService.PlannedSubtask> planned = new ArrayList<>(specs.size());
            List<UUID> keep = new ArrayList<>(specs.size());
            for (SubtaskService.SubtaskSpec spec : specs) {
                planned.add(new SubtaskDelegationService.PlannedSubtask(spec.subtaskId(), spec.title(),
                        spec.sequence(), spec.dependencyIds(), spec.requiredCapabilities(), TaskPhase.DRAFT));
                keep.add(spec.subtaskId());
            }
            return new SubtaskDelegationService.PlanOutcome(planned, keep);
        });
        service = new SubtaskService(delegation, tree, runs,
                new TaskProcessEventService(events), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void planInsertsPendingNodesAndEmitsPlannedEvents() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();

        List<TaskTreeNode> nodes = service.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a1, "抓取邮件", 1, List.of()),
                new SubtaskService.SubtaskSpec(a2, "生成摘要", 2, List.of(a1))));

        assertThat(nodes).hasSize(2);
        assertThat(nodes).allSatisfy(node -> {
            assertThat(node.parentTaskId()).isEqualTo(taskId);
            assertThat(node.status()).isEqualTo("PENDING");
            assertThat(node.updatedAt()).isEqualTo(NOW);
        });
        assertThat(nodes.get(0).sequence()).isEqualTo(1);
        assertThat(nodes.get(1).sequence()).isEqualTo(2);
        assertThat(nodes.get(1).dependencyIds()).containsExactly(a1);

        List<TaskProcessEvent> replayed = events.find(CONTEXT, taskId, runId, 0,
                Set.of(TaskEventVisibility.REQUESTER), 100);
        assertThat(replayed).hasSize(2);
        assertThat(replayed).allSatisfy(event -> assertThat(event.eventType()).isEqualTo("subtask.planned"));
        assertThat(replayed.get(0).sequence()).isEqualTo(7);
        assertThat(replayed.get(1).sequence()).isEqualTo(8);
        assertThat(replayed.get(0).payload()).contains(a1.toString()).contains("抓取邮件");
    }

    @Test
    void planIsDeclarativeSyncKeepingExistingAndRemovingStale() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        service.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a1, "抓取邮件", 1, List.of()),
                new SubtaskService.SubtaskSpec(a2, "生成摘要", 2, List.of())));
        assertThat(events.all).hasSize(2);

        // a2 已推进到 RUNNING：二次 plan 保留现状，不重置、不重发事件。
        TaskTreeNode running = new TaskTreeNode(a2, taskId, 2, "RUNNING", List.of(), NOW.plusSeconds(1));
        when(tree.find(CONTEXT, runId)).thenReturn(List.of(running));
        List<TaskTreeNode> nodes = service.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(a2, "生成摘要", 2, List.of()),
                new SubtaskService.SubtaskSpec(a3, "校对输出", 3, List.of())));

        verify(tree).deleteOthers(CONTEXT, runId, List.of(a2, a3));
        assertThat(nodes).containsExactly(running,
                new TaskTreeNode(a3, taskId, 3, "PENDING", List.of(), NOW));
        // 只有 a3 产生新的 planned 事件（a2 不重发）。
        assertThat(events.all).hasSize(3);
        assertThat(events.all.get(2).eventType()).isEqualTo("subtask.planned");
        assertThat(events.all.get(2).payload()).contains(a3.toString());
    }

    @Test
    void updateStatusRejectsPendingAndBlocked() {
        UUID subtaskId = UUID.randomUUID();
        when(tree.find(CONTEXT, runId)).thenReturn(List.of(
                new TaskTreeNode(subtaskId, taskId, 1, "PENDING", List.of(), NOW)));

        assertThatThrownBy(() -> service.updateStatus(taskId, subtaskId, "BLOCKED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        assertThatThrownBy(() -> service.updateStatus(taskId, subtaskId, "PENDING", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events.all).isEmpty();
        verify(tree, never()).upsert(any(), any(), any());
    }

    @Test
    void updateStatusUpsertsNodeAndEmitsTypedEvent() {
        UUID subtaskId = UUID.randomUUID();
        TaskTreeNode pending = new TaskTreeNode(subtaskId, taskId, 1, "PENDING", List.of(), NOW);
        when(tree.find(CONTEXT, runId)).thenReturn(List.of(pending));

        TaskTreeNode running = service.updateStatus(taskId, subtaskId, "RUNNING", null);
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.parentTaskId()).isEqualTo(taskId);
        assertThat(running.sequence()).isEqualTo(1);
        verify(tree).upsert(CONTEXT, runId, running);

        TaskTreeNode failed = service.updateStatus(taskId, subtaskId, "FAILED", "上游超时");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.dependencyIds()).isEmpty();

        List<TaskProcessEvent> replayed = events.find(CONTEXT, taskId, runId, 0,
                Set.of(TaskEventVisibility.REQUESTER), 100);
        assertThat(replayed).extracting(TaskProcessEvent::eventType)
                .containsExactly("subtask.started", "subtask.failed");
        assertThat(replayed.get(1).payload()).contains("上游超时");
    }

    @Test
    void planRejectsEmptyAndOversizedListsAndUnknownTask() {
        assertThatThrownBy(() -> service.plan(taskId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<SubtaskService.SubtaskSpec> oversized = new ArrayList<>();
        for (int i = 0; i <= SubtaskService.MAX_SUBTASKS; i++) {
            oversized.add(new SubtaskService.SubtaskSpec(UUID.randomUUID(), "t" + i, i + 1, List.of()));
        }
        assertThatThrownBy(() -> service.plan(taskId, oversized))
                .isInstanceOf(IllegalArgumentException.class);
        when(runs.latestRunId(taskId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.plan(taskId,
                List.of(new SubtaskService.SubtaskSpec(UUID.randomUUID(), "t", 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no run");
    }

    @Test
    void updateStatusRejectsUnknownSubtask() {
        when(tree.find(CONTEXT, runId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.updateStatus(taskId, UUID.randomUUID(), "RUNNING", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void updateStatusRejectsSelfReference() {
        assertThatThrownBy(() -> service.updateStatus(taskId, taskId, "RUNNING", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ from the main task");
    }

    @Test
    void rejectsSelfReferenceAndDuplicateSubtaskIds() {
        UUID subtaskId = UUID.randomUUID();
        // G03 起校验下沉至 SubtaskDelegationService（见其同名测试）；此处验证传播。
        when(delegation.plan(any(UUID.class), any(List.class))).thenThrow(
                new IllegalArgumentException("subtask ids must be unique within one plan"));
        assertThatThrownBy(() -> service.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(subtaskId, "重复", 1, List.of()),
                new SubtaskService.SubtaskSpec(subtaskId, "重复", 2, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThat(events.all).isEmpty();
    }

    @Test
    void planRejectsDependencyOutsideThePlan() {
        // G03 起校验下沉至 SubtaskDelegationService（见其同名测试）；此处验证传播与不写事件。
        when(delegation.plan(any(UUID.class), any(List.class))).thenThrow(
                new IllegalArgumentException("dependencyIds must reference subtasks within the same plan"));
        assertThatThrownBy(() -> service.plan(taskId, List.of(
                new SubtaskService.SubtaskSpec(UUID.randomUUID(), "抓取邮件", 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("within the same plan");
        assertThat(events.all).isEmpty();
    }

    @Test
    void planAndStatusRejectTasksWithoutVisibleScope() {
        when(runs.contextForTask(taskId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.plan(taskId,
                List.of(new SubtaskService.SubtaskSpec(UUID.randomUUID(), "t", 1, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no visible scope");
        assertThatThrownBy(() -> service.updateStatus(taskId, UUID.randomUUID(), "RUNNING", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no visible scope");
        verify(tree, never()).deleteOthers(any(), any(), any());
    }

    /** 测试内最小事件仓库：只记录并按授权条件回放。 */
    private static final class RecordingEventRepository implements TaskProcessEventRepository {
        private final List<TaskProcessEvent> all = new ArrayList<>();

        @Override
        public boolean insert(ExecutionContext context, TaskProcessEvent event) {
            all.add(event);
            return true;
        }

        @Override
        public List<TaskProcessEvent> find(ExecutionContext context, UUID taskId, UUID runId, long after,
                Set<TaskEventVisibility> visible, int limit) {
            return all.stream()
                    .filter(event -> event.taskId().equals(taskId) && event.runId().equals(runId))
                    .filter(event -> event.sequence() >= after && visible.contains(event.visibility()))
                    .sorted(java.util.Comparator.comparingLong(TaskProcessEvent::sequence))
                    .limit(limit)
                    .toList();
        }
    }
}
