package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TaskListRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import java.time.Instant;
import java.util.UUID;
import io.agentteams.domain.task.TaskPhase;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public final class TaskController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateTaskRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        PrincipalContext.requireScope(request.spec() == null ? null : request.spec().toString());
        TaskRecord task = service.create(idempotencyKey,
                request.toServiceInput(PrincipalContext.actorOr(request.actor())));
        return ResponseEntity.status(201).body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        TaskRecord task = service.get(id);
        PrincipalContext.requireScope(task.specJson());
        return TaskResponse.from(task);
    }

    @GetMapping
    public CursorPage<TaskListResponse> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction, @RequestParam(required = false) TaskPhase phase,
            @RequestParam(required = false) UUID teamId, @RequestParam(required = false) UUID workerId,
            @RequestParam(required = false) String actor, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false, name = "q") String query) {
        TaskService.TaskListFilter filter = new TaskService.TaskListFilter(phase, teamId, workerId, actor, from, to,
                query);
        return service.list(new CursorPageRequest(cursor, pageSize, sort, direction), filter)
                .map(TaskListResponse::from);
    }

    @PostMapping("/{id}/cancel")
    public TaskResponse cancel(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) CancelTaskRequest request) {
        requireIdempotencyKey(idempotencyKey);
        CancelTaskRequest input = request == null ? new CancelTaskRequest(null, null, null) : request;
        requireExistingTaskScope(id);
        long expectedVersion = input.expectedVersion() == null ? 0 : input.expectedVersion();
        TaskRecord task = service.cancel(id, expectedVersion, idempotencyKey,
                PrincipalContext.actorOr(input.actor()), input.source());
        return TaskResponse.from(task);
    }

    @PostMapping("/{id}/queue")
    public TaskResponse queue(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) QueueTaskRequest request) {
        requireIdempotencyKey(idempotencyKey);
        QueueTaskRequest input = request == null ? new QueueTaskRequest(null) : request;
        requireExistingTaskScope(id);
        long expectedVersion = input.expectedVersion() == null ? 0 : input.expectedVersion();
        TaskRecord queued = PrincipalContext.current().isPresent()
                ? service.queue(id, expectedVersion, idempotencyKey, PrincipalContext.actorOr("api"))
                : service.queue(id, expectedVersion, idempotencyKey);
        return TaskResponse.from(queued);
    }

    @PostMapping("/{id}/retry")
    public TaskResponse retry(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TaskLifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        TaskLifecycleRequest input = request == null ? new TaskLifecycleRequest(null, null, null) : request;
        requireExistingTaskScope(id);
        TaskRecord retried = service.retry(id, expectedVersion(input), idempotencyKey,
                PrincipalContext.actorOr(input.actor()), input.source());
        return TaskResponse.from(retried);
    }

    @PostMapping("/{id}/pause")
    public TaskResponse pause(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TaskLifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        TaskLifecycleRequest input = request == null ? new TaskLifecycleRequest(null, null, null) : request;
        requireExistingTaskScope(id);
        TaskRecord paused = service.pause(id, expectedVersion(input), idempotencyKey,
                PrincipalContext.actorOr(input.actor()), input.source());
        return TaskResponse.from(paused);
    }

    @PostMapping("/{id}/approve")
    public TaskResponse approve(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TaskLifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        TaskLifecycleRequest input = request == null ? new TaskLifecycleRequest(null, null, null) : request;
        requireExistingTaskScope(id);
        TaskRecord approved = service.approve(id, expectedVersion(input), idempotencyKey,
                PrincipalContext.actorOr(input.actor()), input.source());
        return TaskResponse.from(approved);
    }

    @PostMapping("/{id}/reject")
    public TaskResponse reject(@PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) TaskLifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        TaskLifecycleRequest input = request == null ? new TaskLifecycleRequest(null, null, null) : request;
        requireExistingTaskScope(id);
        TaskRecord rejected = service.reject(id, expectedVersion(input), idempotencyKey,
                PrincipalContext.actorOr(input.actor()), input.source());
        return TaskResponse.from(rejected);
    }

    public record CreateTaskRequest(String title, String description, JsonNode spec,
            String actor, String source, String taskType) {

        public CreateTaskRequest(String title, String description, JsonNode spec,
                String actor, String source) {
            this(title, description, spec, actor, source, null);
        }

        TaskService.TaskInput toServiceInput(String authenticatedActor) {
            return new TaskService.TaskInput(title, description, json(spec), authenticatedActor, source, taskType);
        }

        private static String json(JsonNode value) {
            if (value == null || value.isNull()) {
                return "{}";
            }
            if (!value.isObject()) {
                throw new IllegalArgumentException("JSON object is required");
            }
            return value.toString();
        }
    }

    public record CancelTaskRequest(Long expectedVersion, String actor, String source) {
    }

    public record QueueTaskRequest(Long expectedVersion) {
    }

    public record TaskLifecycleRequest(Long expectedVersion, String actor, String source) {
    }

    public record TaskResponse(UUID id, String title, String description, String phase,
            int priority, String taskType, Instant createdAt, Instant updatedAt, long version) {

        static TaskResponse from(TaskRecord task) {
            return new TaskResponse(task.id(), task.title(), task.description(), task.phase().name(),
                    task.priority(), task.taskType(), task.createdAt(), task.updatedAt(), task.version());
        }
    }

    public record TaskListResponse(UUID id, String title, String phase, int priority, String taskType,
            String tenantId, String projectId, String team, String actor, String source,
            UUID teamId, UUID workerId, Instant createdAt, Instant updatedAt, long version) {
        static TaskListResponse from(TaskListRecord task) {
            return new TaskListResponse(task.id(), task.title(), task.phase().name(), task.priority(), task.taskType(),
                    task.tenantId(), task.projectId(), task.team(), task.actor(), task.source(), task.teamId(),
                    task.workerId(), task.createdAt(), task.updatedAt(), task.version());
        }
    }

    private void requireExistingTaskScope(UUID id) {
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(service.get(id).specJson());
        }
    }

    private static void requireRequest(CreateTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
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

    private static long expectedVersion(TaskLifecycleRequest request) {
        return request.expectedVersion() == null ? 0 : request.expectedVersion();
    }
}
