package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.task.TaskPhase;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * G03 D6：子任务一等实体管理。plan 时在 tasks 表创建 kind=SUBTASK 的真实任务行
 * （scope/taskType 继承主任务，requiredCapabilities 供 Team 调度能力匹配），按规格
 * §4.1 表做声明式 re-plan：清单内未终态保留、终态保留不重跑；清单外未出队取消
 * （CANCELLABLE：DRAFT/QUEUED/PAUSED）、终态留存不删行。投影双写（task_subtasks）
 * 与 subtask.* 过程事件仍由 {@link SubtaskService} 负责。
 */
@Service
public class SubtaskDelegationService {
    public static final int MAX_SUBTASKS = SubtaskService.MAX_SUBTASKS;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<TaskPhase> CANCELLABLE = Set.of(
            TaskPhase.DRAFT, TaskPhase.QUEUED, TaskPhase.PAUSED);

    private final FoundationPersistenceService persistence;
    private final Clock clock;

    public SubtaskDelegationService(FoundationPersistenceService persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record PlannedSubtask(UUID subtaskId, String title, int sequence,
            List<UUID> dependencyIds, List<String> requiredCapabilities, TaskPhase phase) {
    }

    /** plan 结果：清单内子任务 + 投影保留清单（清单内 ∪ 清单外真实行，CANCELLED 仍在 DAG）。 */
    public record PlanOutcome(List<PlannedSubtask> planned, List<UUID> projectionKeepIds) {
    }

    @Transactional
    public PlanOutcome plan(UUID taskId, List<SubtaskService.SubtaskSpec> specs) {
        Objects.requireNonNull(taskId, "taskId");
        if (specs == null || specs.isEmpty() || specs.size() > MAX_SUBTASKS) {
            throw new IllegalArgumentException("subtasks size must be between 1 and " + MAX_SUBTASKS);
        }
        List<UUID> requestedIds = specs.stream().map(SubtaskService.SubtaskSpec::subtaskId).toList();
        if (requestedIds.stream().distinct().count() != specs.size()) {
            throw new IllegalArgumentException("subtask ids must be unique within one plan");
        }
        if (requestedIds.contains(taskId)) {
            throw new IllegalArgumentException("subtask id must differ from the main task id");
        }
        Set<UUID> requested = new HashSet<>(requestedIds);
        for (SubtaskService.SubtaskSpec spec : specs) {
            if (!requested.containsAll(spec.dependencyIds())) {
                throw new IllegalArgumentException(
                        "dependencyIds must reference subtasks within the same plan");
            }
        }
        requireNoCycle(specs);
        Instant at = clock.instant();
        return persistence.inTransaction(tx -> {
            // 行锁串行化同任务 re-plan，防止并发 plan 交错
            TaskRecord parent = tx.tasks().findByIdForUpdate(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
            if (parent.isSubtask()) {
                throw new IllegalArgumentException("cannot plan under a subtask: " + taskId);
            }
            Map<UUID, TaskRecord> existing = new LinkedHashMap<>();
            tx.tasks().findByParent(taskId).forEach(child -> existing.put(child.id(), child));
            List<PlannedSubtask> planned = new ArrayList<>(specs.size());
            List<UUID> keepIds = new ArrayList<>(requestedIds);
            for (SubtaskService.SubtaskSpec spec : specs) {
                TaskRecord old = existing.get(spec.subtaskId());
                TaskRecord child;
                if (old == null) {
                    child = new TaskRecord(spec.subtaskId(), spec.title(), "", TaskPhase.DRAFT, 0,
                            childSpecJson(parent.specJson(), taskId, spec), parent.actor(),
                            "subtask-delegation", null, null, at, at, 0, parent.taskType(),
                            "ACTIVE", null, null, taskId, "SUBTASK");
                    tx.tasks().insert(child);
                    FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskCreated",
                            childPayload(child, taskId, spec).toString(), at, child.version());
                } else {
                    // 规格 §4.1：清单内保留现状（终态/未终态均不重置、不重跑）
                    child = old;
                }
                keepIds.add(child.id());
                planned.add(new PlannedSubtask(child.id(), spec.title(), spec.sequence(),
                        List.copyOf(spec.dependencyIds()), List.copyOf(spec.requiredCapabilities()),
                        child.phase()));
            }
            for (TaskRecord stale : existing.values()) {
                if (requested.contains(stale.id())) {
                    continue;
                }
                // 规格 §4.1：清单外未出队 → CANCELLED（终态留存不删行）
                if (CANCELLABLE.contains(stale.phase())) {
                    tx.tasks().updatePhase(stale.id(), TaskPhase.CANCELLED, stale.version(), at);
                    FoundationPersistenceService.appendEvent(tx, "task", stale.id(), "TaskPhaseChanged",
                            idPayload(stale.id()), at, stale.version() + 1);
                }
                keepIds.add(stale.id());
            }
            return new PlanOutcome(List.copyOf(planned), List.copyOf(keepIds));
        });
    }

    /** 迭代 DFS 环检测：依赖图有环即拒绝（异常消息含 cycle，供调用方识别）。 */
    private static void requireNoCycle(List<SubtaskService.SubtaskSpec> specs) {
        Map<UUID, List<UUID>> edges = new LinkedHashMap<>();
        for (SubtaskService.SubtaskSpec spec : specs) {
            edges.put(spec.subtaskId(), List.copyOf(spec.dependencyIds()));
        }
        Set<UUID> visited = new HashSet<>();
        for (UUID root : edges.keySet()) {
            if (visited.contains(root)) {
                continue;
            }
            Deque<UUID> path = new ArrayDeque<>();
            Deque<Iterator<UUID>> stack = new ArrayDeque<>();
            Set<UUID> inStack = new HashSet<>();
            path.push(root);
            inStack.add(root);
            stack.push(edges.get(root).iterator());
            while (!stack.isEmpty()) {
                Iterator<UUID> current = stack.peek();
                if (!current.hasNext()) {
                    stack.pop();
                    UUID done = path.pop();
                    inStack.remove(done);
                    visited.add(done);
                    continue;
                }
                UUID next = current.next();
                if (inStack.contains(next)) {
                    throw new IllegalArgumentException("subtask dependency cycle detected at " + next);
                }
                if (visited.contains(next)) {
                    continue;
                }
                path.push(next);
                inStack.add(next);
                stack.push(edges.get(next).iterator());
            }
        }
    }

    /** 子任务 spec：继承主任务顶层 scope/taskType，覆盖 requiredCapabilities；生命周期键不继承。 */
    private static String childSpecJson(String parentSpecJson, UUID parentTaskId,
            SubtaskService.SubtaskSpec spec) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(parentSpecJson);
            root.remove("approvalGranted");
            root.remove("cancelReason");
            if (spec.requiredCapabilities().isEmpty()) {
                root.remove("requiredCapabilities");
            } else {
                ArrayNode capabilities = root.putArray("requiredCapabilities");
                spec.requiredCapabilities().forEach(capabilities::add);
            }
            root.put("parentTaskId", parentTaskId.toString()).put("kind", "SUBTASK");
            // 依赖 id 落在子任务 spec 顶层（任务 3 gate 读取此处；投影 task_subtasks 仅作展示）
            if (spec.dependencyIds().isEmpty()) {
                root.remove("dependencyIds");
            } else {
                ArrayNode dependencies = root.putArray("dependencyIds");
                spec.dependencyIds().forEach(id -> dependencies.add(id.toString()));
            }
            return JSON.writeValueAsString(root);
        } catch (IOException error) {
            throw new IllegalArgumentException("parent spec is not valid JSON", error);
        }
    }

    private static ObjectNode childPayload(TaskRecord child, UUID parentTaskId,
            SubtaskService.SubtaskSpec spec) {
        ObjectNode payload = JSON.createObjectNode()
                .put("taskId", child.id().toString())
                .put("parentTaskId", parentTaskId.toString())
                .put("title", spec.title())
                .put("sequence", spec.sequence());
        if (!spec.requiredCapabilities().isEmpty()) {
            ArrayNode capabilities = payload.putArray("requiredCapabilities");
            spec.requiredCapabilities().forEach(capabilities::add);
        }
        return payload;
    }

    private static String idPayload(UUID id) {
        return "{\"id\":\"" + id + "\"}";
    }
}
