package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.task.SubtaskService;
import io.agentteams.controlplane.task.TaskTreeNode;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 子任务拆解与状态推进端点：agent 经 agentteams-task MCP 工具或直接 API 调用。
 * 鉴权沿任务生命周期端点模式——先取已存在任务（未知任务 → 404），有 principal 时
 * 以任务 specJson 校验调用方作用域；无鉴权部署（无 principal）时跳过作用域校验，
 * 归属仍由 SubtaskService 内部的 contextForTask 兜底。写操作一律要求幂等键。
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/subtasks")
public final class SubtaskController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TaskService tasks;
    private final SubtaskService subtasks;

    public SubtaskController(TaskService tasks, SubtaskService subtasks) {
        this.tasks = tasks;
        this.subtasks = subtasks;
    }

    @PutMapping
    public PlanResponse plan(@PathVariable UUID taskId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody PlanRequest request) {
        requireIdempotencyKey(idempotencyKey);
        requireExistingTaskScope(taskId);
        if (request == null || request.subtasks() == null) {
            throw new IllegalArgumentException("subtasks is required");
        }
        return new PlanResponse(subtasks.plan(taskId, request.subtasks()));
    }

    @PutMapping("/{subtaskId}/status")
    public TaskTreeNode updateStatus(@PathVariable UUID taskId, @PathVariable UUID subtaskId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody StatusUpdateRequest request) {
        requireIdempotencyKey(idempotencyKey);
        requireExistingTaskScope(taskId);
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        return subtasks.updateStatus(taskId, subtaskId, request.status(), request.note());
    }

    public record PlanRequest(List<SubtaskService.SubtaskSpec> subtasks) {
    }

    public record PlanResponse(List<TaskTreeNode> nodes) {
    }

    public record StatusUpdateRequest(String status, String note) {
    }

    /** 无条件取已存在任务（未知 → 404，两种部署一致），有 principal 时校验作用域。 */
    private void requireExistingTaskScope(UUID id) {
        TaskRecord task = tasks.get(id);
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(task.specJson());
        }
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }
}
