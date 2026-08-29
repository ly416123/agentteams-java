package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Strict management write surface; the legacy task routes keep their compatibility defaults. */
@RestController
@RequestMapping("/api/v1/management/tasks")
public final class StrictTaskManagementController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final TaskService service;

    public StrictTaskManagementController(TaskService service) {
        this.service = service;
    }

    @PostMapping("/{id}/cancel")
    public TaskController.TaskResponse cancel(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.CancelTaskRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        TaskRecord task = service.cancel(id, request.expectedVersion(), idempotencyKey,
                PrincipalContext.actorOr(actor(request.actor())), request.source());
        return TaskController.TaskResponse.from(task);
    }

    @PostMapping("/{id}/queue")
    public TaskController.TaskResponse queue(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.QueueTaskRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        TaskRecord task = PrincipalContext.current().isPresent()
                ? service.queue(id, request.expectedVersion(), idempotencyKey, PrincipalContext.actorOr("api"))
                : service.queue(id, request.expectedVersion(), idempotencyKey);
        return TaskController.TaskResponse.from(task);
    }

    @PostMapping("/{id}/retry")
    public TaskController.TaskResponse retry(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.TaskLifecycleRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        return TaskController.TaskResponse.from(service.retry(id, request.expectedVersion(), idempotencyKey,
                PrincipalContext.actorOr(actor(request.actor())), request.source()));
    }

    @PostMapping("/{id}/pause")
    public TaskController.TaskResponse pause(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.TaskLifecycleRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        return TaskController.TaskResponse.from(service.pause(id, request.expectedVersion(), idempotencyKey,
                PrincipalContext.actorOr(actor(request.actor())), request.source()));
    }

    @PostMapping("/{id}/approve")
    public TaskController.TaskResponse approve(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.TaskLifecycleRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        return TaskController.TaskResponse.from(service.approve(id, request.expectedVersion(), idempotencyKey,
                PrincipalContext.actorOr(actor(request.actor())), request.source()));
    }

    @PostMapping("/{id}/reject")
    public TaskController.TaskResponse reject(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody TaskController.TaskLifecycleRequest request) {
        requireKey(idempotencyKey);
        requireVersion(request == null ? null : request.expectedVersion());
        return TaskController.TaskResponse.from(service.reject(id, request.expectedVersion(), idempotencyKey,
                PrincipalContext.actorOr(actor(request.actor())), request.source()));
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
        }
    }

    private static void requireVersion(Long value) {
        if (value == null || value < 0) throw new IllegalArgumentException("expectedVersion is required");
    }

    private static String actor(String value) {
        return value == null || value.isBlank() ? "api" : value.trim();
    }
}
