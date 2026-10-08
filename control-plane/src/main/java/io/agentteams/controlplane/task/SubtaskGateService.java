package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * G03 D5/D3：子任务依赖 gate 与汇总轮触发。由调度器 tick 驱动（1s 轮询），全部操作
 * 幂等：gate 仅作用于 DRAFT 子任务（依赖全 SUCCEEDED 才放行）；汇总触发仅作用于
 * phase=WAITING_SUBTASKS 的 MAIN 任务且要求全部子任务 SUCCEEDED（D3 硬约束），转移复用
 * G02 的重排队边并追加 TaskChildrenCompleted 聚合事件。
 */
@Service
public class SubtaskGateService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final Clock clock;

    public SubtaskGateService(FoundationPersistenceService persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 调度 tick 前置动作：放行就绪子任务并触发可汇总的 parent，返回动作数。 */
    public int tick(int limit) {
        int actions = 0;
        for (UUID parentId : persistence.inTransaction(
                tx -> tx.tasks().findParentIdsWithDraftChildren(limit))) {
            actions += releaseReadyChildren(parentId);
        }
        for (UUID parentId : persistence.inTransaction(
                tx -> tx.tasks().findParentIdsAllChildrenSucceeded(limit))) {
            if (releaseParentForAggregation(parentId)) {
                actions++;
            }
        }
        return actions;
    }

    /** 放行依赖已满足的 DRAFT 子任务（依赖 id 取自子任务 specJson 顶层），返回本 tick 动作数。
     * 依赖已进入不可恢复终态（CANCELLED/REJECTED）的 DRAFT 子任务直接置 FAILED——
     * 永久 DRAFT 滞留会使依赖硬约束空转且汇总永不触发；FAILED 可经 G02 retry 边
     * 恢复，失败对请求方可见。 */
    @Transactional
    public int releaseReadyChildren(UUID parentId) {
        return persistence.inTransaction(tx -> {
            List<TaskRecord> children = tx.tasks().findByParent(parentId);
            if (children.isEmpty()) {
                return 0;
            }
            Map<UUID, TaskPhase> phaseById = children.stream()
                    .collect(Collectors.toMap(TaskRecord::id, TaskRecord::phase));
            Instant at = clock.instant();
            int released = 0;
            for (TaskRecord child : children) {
                if (child.phase() != TaskPhase.DRAFT) {
                    continue;
                }
                List<UUID> dependencies = dependencyIds(child.specJson());
                if (dependencies.stream().map(phaseById::get).anyMatch(
                        phase -> phase == TaskPhase.CANCELLED || phase == TaskPhase.REJECTED)) {
                    TaskRecord failed = tx.tasks().updatePhase(child.id(), TaskPhase.FAILED,
                            child.version(), at);
                    FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskPhaseChanged",
                            idPayload(child.id()), at, failed.version());
                    released++;
                    continue;
                }
                boolean ready = dependencies.stream()
                        .allMatch(id -> phaseById.get(id) == TaskPhase.SUCCEEDED);
                if (!ready) {
                    continue;
                }
                TaskRecord updated = tx.tasks().updateState(TeamOrchestrationService.copy(child,
                        withDependencyResults(child, children, dependencies), at, child.version(), TaskPhase.QUEUED),
                        child.version());
                FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskPhaseChanged",
                        idPayload(child.id()), at, updated.version());
                released++;
            }
            return released;
        });
    }

    private static String withDependencyResults(TaskRecord child, List<TaskRecord> children, List<UUID> dependencies) {
        if (dependencies.isEmpty()) return child.specJson();
        try {
            JsonNode parsed = JSON.readTree(child.specJson());
            ObjectNode root = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed : JSON.createObjectNode();
            ObjectNode input = root.path("inputJson").isObject()
                    ? ((ObjectNode) root.path("inputJson")).deepCopy() : JSON.createObjectNode();
            ArrayNode results = input.putArray("dependencyResults");
            for (UUID dependencyId : dependencies) {
                children.stream().filter(candidate -> candidate.id().equals(dependencyId)).findFirst()
                        .ifPresent(dependency -> {
                            String result = childResult(dependency.specJson());
                            if (!result.isBlank()) {
                                results.addObject().put("taskId", dependency.id().toString())
                                        .put("title", dependency.title()).put("result", result);
                            }
                        });
            }
            root.set("inputJson", input);
            return root.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("child task spec is not valid JSON", error);
        }
    }

    /** 子任务全部 SUCCEEDED 且主任务处于等待态 → 系统转 QUEUED 进入汇总轮。 */
    @Transactional
    public boolean releaseParentForAggregation(UUID parentId) {
        return persistence.inTransaction(tx -> {
            TaskRecord parent = tx.tasks().findByIdForUpdate(parentId).orElse(null);
            if (parent == null || parent.isSubtask()
                    || (parent.phase() != TaskPhase.WAITING_SUBTASKS
                        && !(parent.phase() == TaskPhase.SUCCEEDED
                            && !TeamOrchestrationService.hasTeam(parent.specJson())))) {
                return false;
            }
            if (tx.tasks().findByParent(parentId).isEmpty()) {
                return false;
            }
            // D3 硬约束复查（行锁内）：存在任何非 SUCCEEDED 子任务则不触发
            if (tx.tasks().countByParentNotPhase(parentId, TaskPhase.SUCCEEDED) > 0) {
                return false;
            }
            // 防重入（行锁内复查）：TaskChildrenCompleted 已存在说明该子任务代的
            // 自动聚合已发生过——汇总轮完成后主任务重新 SUCCEEDED 时触发条件
            // 在数值上依然成立，缺此闸门调度 tick 会无限重排汇总轮。后续重排
            // 由评审 retry 等显式动作驱动，不经由本 gate。
            if (tx.domainEvents().existsByAggregateAndType("task", parentId, "TaskChildrenCompleted")) {
                return false;
            }
            Instant at = clock.instant();
            List<TaskRecord> children = tx.tasks().findByParent(parentId);
            TaskRecord queued;
            if (TeamOrchestrationService.hasTeam(parent.specJson())) {
                queued = tx.tasks().updateState(
                        TeamOrchestrationService.copy(parent, aggregateSpec(parent, children), at, parent.version(),
                                TaskPhase.QUEUED),
                        parent.version());
            } else {
                queued = tx.tasks().updatePhase(parentId, TaskPhase.QUEUED, parent.version(), at);
            }
            FoundationPersistenceService.appendEvent(tx, "task", parentId, "TaskChildrenCompleted",
                    idPayload(parentId), at, tx.tasks().incrementVersion(parentId));
            return queued.phase() == TaskPhase.QUEUED;
        });
    }

    private static String aggregateSpec(TaskRecord parent, List<TaskRecord> children) {
        try {
            JsonNode parsed = JSON.readTree(parent.specJson());
            ObjectNode root = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed : JSON.createObjectNode();
            ObjectNode input = root.path("inputJson").isObject()
                    ? ((ObjectNode) root.path("inputJson")).deepCopy() : JSON.createObjectNode();
            // Tool requirements are attempt-scoped: never carry plan tools into
            // aggregation, but allow a task to declare a narrower aggregate tool set.
            JsonNode aggregatePrompt = input.remove("aggregatePrompt");
            if (aggregatePrompt != null && aggregatePrompt.isTextual() && !aggregatePrompt.asText().isBlank()) {
                input.put("prompt", aggregatePrompt.asText());
            }
            JsonNode aggregateTools = input.remove("aggregateMcpTools");
            input.remove("requiredMcpTools");
            if (aggregateTools != null && aggregateTools.isArray() && !aggregateTools.isEmpty()) {
                input.set("requiredMcpTools", aggregateTools.deepCopy());
            }
            ArrayNode results = input.putArray("childResults");
            for (TaskRecord child : children) {
                ObjectNode item = results.addObject().put("taskId", child.id().toString())
                        .put("title", child.title());
                String result = childResult(child.specJson());
                if (!result.isBlank()) item.put("result", result);
            }
            root.set("inputJson", input);
            root.put("teamRole", "LEADER").put("orchestrationPhase", "AGGREGATE");
            return root.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("parent task spec is not valid JSON", error);
        }
    }

    private static String childResult(String specJson) {
        try {
            return JSON.readTree(specJson).path("lastResultJson").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<UUID> dependencyIds(String specJson) {
        try {
            JsonNode node = JSON.readTree(specJson).path("dependencyIds");
            List<UUID> ids = new ArrayList<>();
            node.forEach(entry -> ids.add(UUID.fromString(entry.asText())));
            return ids;
        } catch (Exception error) {
            return List.of();
        }
    }

    private static String idPayload(UUID id) {
        return "{\"taskId\":\"" + id + "\"}";
    }
}
