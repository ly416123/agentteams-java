package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.TaskAdjustmentRecord;
import io.agentteams.controlplane.task.TaskAdjustmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 补充要求端点（G02 D5）：content-only 非破坏性调整。 */
@RestController
public final class TaskAdjustmentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final TaskAdjustmentService service;

    public TaskAdjustmentController(TaskAdjustmentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/tasks/{id}/adjustments")
    public ResponseEntity<AdjustmentResponse> create(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody CreateAdjustmentRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        TaskAdjustmentService.AdjustmentInput input = request.toInput();
        TaskAdjustmentRecord record = service.create(id, input, idempotencyKey);
        return ResponseEntity.status(201).body(AdjustmentResponse.from(record));
    }

    @GetMapping("/api/v1/tasks/{id}/adjustments")
    public List<AdjustmentResponse> list(@PathVariable UUID id) {
        return service.list(id).stream().map(AdjustmentResponse::from).toList();
    }

    /** 上游 body 结构（corp-agent）：{"content":{"requirement":"..."}}；actor/source 可选。 */
    public record CreateAdjustmentRequest(JsonNode content, String actor, String source) {

        TaskAdjustmentService.AdjustmentInput toInput() {
            if (content == null) {
                throw new IllegalArgumentException("content is required");
            }
            String requirement = content.path("requirement").asText("");
            return new TaskAdjustmentService.AdjustmentInput(requirement, actor, source);
        }
    }

    public record AdjustmentResponse(UUID id, UUID taskId, JsonNode content, String actor, String source,
            UUID consumedRunId, java.time.Instant createdAt, java.time.Instant updatedAt, long version) {

        static AdjustmentResponse from(TaskAdjustmentRecord record) {
            JsonNode content;
            try {
                content = new com.fasterxml.jackson.databind.ObjectMapper().readTree(record.contentJson());
            } catch (Exception error) {
                throw new IllegalArgumentException("stored adjustment content is not valid JSON", error);
            }
            return new AdjustmentResponse(record.id(), record.taskId(), content, record.actor(), record.source(),
                    record.consumedRunId(), record.createdAt(), record.updatedAt(), record.version());
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
