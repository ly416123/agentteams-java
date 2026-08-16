package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskService;
import java.time.Instant;
import java.util.UUID;
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
        TaskRecord task = service.create(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return TaskResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    public TaskResponse cancel(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) CancelTaskRequest request) {
        requireIdempotencyKey(idempotencyKey);
        CancelTaskRequest input = request == null ? new CancelTaskRequest(null, null, null) : request;
        long expectedVersion = input.expectedVersion() == null ? 0 : input.expectedVersion();
        TaskRecord task = service.cancel(id, expectedVersion, idempotencyKey, input.actor(), input.source());
        return TaskResponse.from(task);
    }

    public record CreateTaskRequest(String title, String description, JsonNode spec,
            String actor, String source) {

        TaskService.TaskInput toServiceInput() {
            return new TaskService.TaskInput(title, description, json(spec), actor, source);
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

    public record TaskResponse(UUID id, String title, String description, String phase,
            int priority, Instant createdAt, Instant updatedAt, long version) {

        static TaskResponse from(TaskRecord task) {
            return new TaskResponse(task.id(), task.title(), task.description(), task.phase().name(),
                    task.priority(), task.createdAt(), task.updatedAt(), task.version());
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
}
