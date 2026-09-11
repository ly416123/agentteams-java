package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 子任务投影与协作语义事件：声明式拆解同步 + 状态推进，双写任务树（task_subtasks）
 * 与过程事件（task_process_events，subtask.* 类型，经 {@code nextSequence} 与 runtime
 * 事件同表同序）。公理一：本服务只写观测投影与事件，真实任务行（G03 一等实体）的
 * 创建/终态由 {@link SubtaskDelegationService} 与平台执行链路驱动；
 * 公理二：状态枚举与数量上限在此二次校验，不信任调用方。
 */
@Service
public class SubtaskService {
    public static final int MAX_SUBTASKS = 20;
    static final Set<String> UPDATABLE_STATUSES = Set.of("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    /** 状态 → 协作事件类型（RUNNING 状态对应开始事件，与规格决策表一致）。 */
    private static final Map<String, String> EVENT_BY_STATUS = Map.of(
            "RUNNING", "subtask.started",
            "SUCCEEDED", "subtask.succeeded",
            "FAILED", "subtask.failed",
            "CANCELLED", "subtask.cancelled");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SubtaskDelegationService delegation;
    private final TaskTreeRepository tree;
    private final TaskRunObservationRepository runs;
    private final TaskProcessEventService processEvents;
    private final Clock clock;

    public SubtaskService(SubtaskDelegationService delegation, TaskTreeRepository tree,
            TaskRunObservationRepository runs, TaskProcessEventService processEvents, Clock clock) {
        this.delegation = Objects.requireNonNull(delegation, "delegation");
        this.tree = Objects.requireNonNull(tree, "tree");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.processEvents = Objects.requireNonNull(processEvents, "processEvents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds,
            List<String> requiredCapabilities) {
        public SubtaskSpec {
            Objects.requireNonNull(subtaskId, "subtaskId");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
            Objects.requireNonNull(dependencyIds, "dependencyIds");
            requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        }

        /** 二期 4 参兼容：无能力要求。 */
        public SubtaskSpec(UUID subtaskId, String title, int sequence, List<UUID> dependencyIds) {
            this(subtaskId, title, sequence, dependencyIds, List.of());
        }
    }

    /**
     * 全量声明式同步：真实任务行的创建/取消/保留由 {@link SubtaskDelegationService#plan}
     * 完成（规格 §4.1 表）；本方法随后收敛投影——清单内 upsert 并对新增发 subtask.planned，
     * 清单外行（含被平台置 CANCELLED 的）保留投影不删，仅清理无真实任务行的孤儿投影。
     * 事务内执行：nextSequence 的 FOR UPDATE 锁持有到提交，与并发的 runtime 事件
     * 写入串行化，避免 UNIQUE(run_id, sequence) 冲突静默丢事件。
     */
    @Transactional
    public List<TaskTreeNode> plan(UUID taskId, List<SubtaskSpec> specs) {
        Objects.requireNonNull(taskId, "taskId");
        if (specs == null || specs.isEmpty() || specs.size() > MAX_SUBTASKS) {
            throw new IllegalArgumentException("subtasks size must be between 1 and " + MAX_SUBTASKS);
        }
        ExecutionContext context = requireTaskContext(taskId);
        SubtaskDelegationService.PlanOutcome outcome = delegation.plan(taskId, specs);
        // G03：DRAFT 主任务允许入队前预拆解（无 run 可投影）——真实子任务行已由
        // delegation.plan 建立；投影树与观测事件留给入队后的下一次 plan 同步补齐
        // （与 listProjection 的「无 run 返回空表」容忍一致，真实状态以 tasks 行为准）。
        var latestRun = runs.latestRunId(taskId);
        if (latestRun.isEmpty()) {
            return outcome.planned().stream()
                    .map(planned -> new TaskTreeNode(planned.subtaskId(), taskId, planned.sequence(),
                            "PENDING", List.copyOf(planned.dependencyIds()), clock.instant()))
                    .toList();
        }
        UUID runId = latestRun.get();
        tree.deleteOthers(context, runId, outcome.projectionKeepIds());
        Map<UUID, TaskTreeNode> existingById = tree.find(context, runId).stream()
                .collect(Collectors.toMap(TaskTreeNode::taskId, Function.identity()));
        List<TaskTreeNode> result = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            SubtaskSpec spec = specs.get(index);
            TaskTreeNode existing = existingById.get(spec.subtaskId());
            TaskTreeNode node = existing != null ? existing
                    : new TaskTreeNode(spec.subtaskId(), taskId, spec.sequence(), "PENDING",
                            List.copyOf(spec.dependencyIds()), clock.instant());
            tree.upsert(context, runId, node);
            if (existing == null) {
                ObjectNode payload = JSON.createObjectNode()
                        .put("subtaskId", spec.subtaskId().toString())
                        .put("title", spec.title())
                        .put("sequence", spec.sequence());
                appendEvent(context, taskId, runId, "subtask.planned", payload);
            }
            result.add(node);
        }
        return List.copyOf(result);
    }

    /**
     * 推进子任务状态并发出类型化事件（subtask.started/succeeded/failed/cancelled）。
     * 不接受 PENDING/BLOCKED，也不设状态迁移单向约束——agent 自治语义，见规格决策表。
     */
    @Transactional
    public TaskTreeNode updateStatus(UUID taskId, UUID subtaskId, String status, String note) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(subtaskId, "subtaskId");
        if (subtaskId.equals(taskId)) {
            throw new IllegalArgumentException("subtask id must differ from the main task id");
        }
        if (status == null || !UPDATABLE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + UPDATABLE_STATUSES);
        }
        ExecutionContext context = requireTaskContext(taskId);
        UUID runId = requireLatestRun(taskId);
        TaskTreeNode existing = tree.find(context, runId).stream()
                .filter(node -> node.taskId().equals(subtaskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("subtask not found: " + subtaskId));
        TaskTreeNode node = new TaskTreeNode(subtaskId, existing.parentTaskId(), existing.sequence(),
                status, existing.dependencyIds(), clock.instant());
        tree.upsert(context, runId, node);
        ObjectNode payload = JSON.createObjectNode().put("subtaskId", subtaskId.toString());
        if (note != null && !note.isBlank()) {
            payload.put("note", note);
        }
        appendEvent(context, taskId, runId, EVENT_BY_STATUS.get(status), payload);
        return node;
    }

    private void appendEvent(ExecutionContext context, UUID taskId, UUID runId, String eventType,
            ObjectNode payload) {
        long sequence = runs.nextSequence(runId);
        processEvents.append(context, new TaskProcessEvent(UUID.randomUUID(), taskId, runId, sequence,
                eventType, TaskEventVisibility.REQUESTER, clock.instant(), "subtask-api",
                payload.toString(), null));
    }

    /**
     * 投影读取（G03 GET 列表用）：无 run/无 scope 的任务返回空表（真实状态以 tasks 行 phase 为准）。
     */
    public List<TaskTreeNode> listProjection(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        var runId = runs.latestRunId(taskId);
        var context = runs.contextForTask(taskId);
        if (runId.isEmpty() || context.isEmpty()) {
            return List.of();
        }
        return tree.find(context.get(), runId.get());
    }

    private ExecutionContext requireTaskContext(UUID taskId) {
        return runs.contextForTask(taskId).orElseThrow(() ->
                new IllegalArgumentException("task has no visible scope: " + taskId));
    }

    private UUID requireLatestRun(UUID taskId) {
        return runs.latestRunId(taskId).orElseThrow(() ->
                new IllegalArgumentException("task has no run yet: " + taskId));
    }
}
