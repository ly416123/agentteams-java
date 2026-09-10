package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.TaskResultVersionRecord;
import io.agentteams.controlplane.task.TaskResultVersionService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务结果版本端点（G02 D1-D4）：版本列表、详情与评审。
 * 评审请求体对齐上游 corp-agent：{"status":"accepted|revision_required","comment":"..."}；
 * decision 字段为自研显式别名，二者取一。
 */
@RestController
public final class TaskResultController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskResultVersionService service;

    public TaskResultController(TaskResultVersionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/tasks/{id}/results")
    public List<TaskResultVersionResponse> list(@PathVariable UUID id) {
        return service.list(id).stream().map(TaskResultVersionResponse::from).toList();
    }

    @GetMapping("/api/v1/tasks/{id}/results/{resultId}")
    public TaskResultVersionResponse get(@PathVariable UUID id, @PathVariable UUID resultId) {
        return TaskResultVersionResponse.from(service.get(id, resultId));
    }

    @PostMapping("/api/v1/tasks/{id}/results/{resultId}/review")
    public ResponseEntity<TaskResultVersionResponse> review(
            @PathVariable UUID id,
            @PathVariable UUID resultId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody ReviewTaskResultRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        TaskResultVersionRecord reviewed = service.review(id, resultId,
                new TaskResultVersionService.ReviewCommand(request.resolvedDecision(), request.comment(),
                        request.actor()),
                request.expectedVersion(), idempotencyKey);
        return ResponseEntity.ok(TaskResultVersionResponse.from(reviewed));
    }

    public record ReviewTaskResultRequest(String status, String decision, String comment, String actor,
            Long expectedVersion) {

        String resolvedDecision() {
            return decision != null && !decision.isBlank() ? decision : status;
        }
    }

    public record TaskResultVersionResponse(UUID id, UUID taskId, UUID runId, UUID manifestId, UUID subtaskId,
            int seq, String status, String summary, JsonNode content, String submittedBy,
            java.time.Instant submittedAt, String reviewActor, String reviewComment,
            java.time.Instant reviewedAt, long version) {

        static TaskResultVersionResponse from(TaskResultVersionRecord record) {
            JsonNode content = null;
            if (record.contentJson() != null) {
                try {
                    content = JSON.readTree(record.contentJson());
                } catch (Exception error) {
                    throw new IllegalArgumentException("stored result content is not valid JSON", error);
                }
            }
            return new TaskResultVersionResponse(record.id(), record.taskId(), record.runId(), record.manifestId(),
                    record.subtaskId(), record.seq(), record.status(), record.summary(), content,
                    record.submittedBy(), record.submittedAt(), record.reviewActor(), record.reviewComment(),
                    record.reviewedAt(), record.version());
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
