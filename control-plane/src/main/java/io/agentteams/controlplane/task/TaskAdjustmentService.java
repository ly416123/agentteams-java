package io.agentteams.controlplane.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.CreateTaskAdjustmentCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAdjustmentRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 补充要求（G02 D5）：content-only 非破坏性调整。
 * 持久化 + TaskAdjusted 事件；运行中任务的实时推送为 best-effort（经事件通道），
 * 下次执行组装上下文时经 {@link #consumePending} 幂等并入。
 */
@Service
public final class TaskAdjustmentService {

    /** 补充要求正文上限：防止把调整通道当成无限文档存储。 */
    static final int MAX_REQUIREMENT_LENGTH = 20_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final ResourceAuthorizationService authorization;

    @Autowired
    public TaskAdjustmentService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            ObjectProvider<ResourceAuthorizationService> authorization) {
        this(persistence, idempotency, Clock.systemUTC(), authorization.getIfAvailable());
    }

    TaskAdjustmentService(FoundationPersistenceService persistence, IdempotencyService idempotency, Clock clock,
            ResourceAuthorizationService authorization) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authorization = authorization;
    }

    public record AdjustmentInput(String requirement, String actor, String source) {
    }

    public TaskAdjustmentRecord create(UUID taskId, AdjustmentInput input, String idempotencyKey) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(input, "input");
        if (input.requirement() == null || input.requirement().isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        String requirement = input.requirement().trim();
        if (requirement.length() > MAX_REQUIREMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "requirement must be at most " + MAX_REQUIREMENT_LENGTH + " characters");
        }
        authorizeTask(taskId);
        String actor = defaultText(input.actor(), "api");
        String source = defaultText(input.source(), "rest");
        String content = contentJson(requirement);
        String requestHash = idempotency.requestHash(taskId.toString(), content, actor, source);
        return persistence.createTaskAdjustment(new CreateTaskAdjustmentCommand(UUID.randomUUID(), taskId,
                content, actor, source, idempotency.requireKey(idempotencyKey), requestHash, clock.instant()));
    }

    public List<TaskAdjustmentRecord> list(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        requireVisible(taskId);
        return persistence.inTransaction(tx -> tx.taskAdjustments().findByTask(taskId, 1000));
    }

    /** 执行上下文组装时调用：把未消费调整并入指定 run 并回填 consumed_run_id。 */
    public int consumePending(UUID taskId, UUID runId) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        return persistence.inTransaction(tx -> {
            List<UUID> pendingIds = tx.taskAdjustments().pendingByTask(taskId).stream()
                    .map(TaskAdjustmentRecord::id).toList();
            return tx.taskAdjustments().markConsumed(pendingIds, runId, clock.instant());
        });
    }

    private String contentJson(String requirement) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("requirement", requirement));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("adjustment requirement could not be encoded", error);
        }
    }

    private void authorizeTask(UUID taskId) {
        if (authorization == null && PrincipalContext.current().isEmpty()) {
            return;
        }
        io.agentteams.controlplane.persistence.TaskRecord task = persistence.findTask(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("task", taskId));
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(task.specJson());
            if (authorization != null) {
                authorization.require(ResourceAction.TASK_OPERATE,
                        PrincipalContext.current().orElseThrow().scope());
            }
        }
    }

    private void requireVisible(UUID taskId) {
        if (PrincipalContext.current().isPresent()) {
            io.agentteams.controlplane.persistence.TaskRecord task = persistence.findTask(taskId)
                    .orElseThrow(() -> new ResourceNotFoundException("task", taskId));
            PrincipalContext.requireScope(task.specJson());
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
