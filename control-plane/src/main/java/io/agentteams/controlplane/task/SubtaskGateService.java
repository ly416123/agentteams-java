package io.agentteams.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * phase=SUCCEEDED 的 MAIN 任务且要求全部子任务 SUCCEEDED（D3 硬约束），转移复用
 * G02 SUCCEEDED→QUEUED 边并追加 TaskChildrenCompleted 聚合事件。
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

    /** 放行依赖已满足的 DRAFT 子任务（依赖 id 取自子任务 specJson 顶层），返回本 tick 放行数。 */
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
                boolean ready = dependencyIds(child.specJson()).stream()
                        .allMatch(id -> phaseById.get(id) == TaskPhase.SUCCEEDED);
                if (!ready) {
                    continue;
                }
                TaskRecord updated = tx.tasks().updatePhase(child.id(), TaskPhase.QUEUED,
                        child.version(), at);
                FoundationPersistenceService.appendEvent(tx, "task", child.id(), "TaskPhaseChanged",
                        idPayload(child.id()), at, updated.version());
                released++;
            }
            return released;
        });
    }

    /** 子任务全部 SUCCEEDED 且主任务处于拆解后 SUCCEEDED → 系统转 QUEUED 进入汇总轮。 */
    @Transactional
    public boolean releaseParentForAggregation(UUID parentId) {
        return persistence.inTransaction(tx -> {
            TaskRecord parent = tx.tasks().findByIdForUpdate(parentId).orElse(null);
            if (parent == null || parent.isSubtask() || parent.phase() != TaskPhase.SUCCEEDED) {
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
            TaskRecord queued = tx.tasks().updatePhase(parentId, TaskPhase.QUEUED, parent.version(), at);
            FoundationPersistenceService.appendEvent(tx, "task", parentId, "TaskChildrenCompleted",
                    idPayload(parentId), at, tx.tasks().incrementVersion(parentId));
            return queued.phase() == TaskPhase.QUEUED;
        });
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
