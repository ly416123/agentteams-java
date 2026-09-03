package io.agentteams.controlplane.api;

import io.agentteams.controlplane.template.WorkerTemplate;
import io.agentteams.controlplane.template.WorkerTemplateInstance;
import io.agentteams.controlplane.template.WorkerTemplateRevision;
import io.agentteams.controlplane.template.WorkerTemplateService;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/worker-templates")
public final class WorkerTemplateController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final WorkerTemplateService service;

    public WorkerTemplateController(WorkerTemplateService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TemplateResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId,
            @RequestBody CreateTemplateRequest request) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        if (request == null) throw new IllegalArgumentException("request body is required");
        return ResponseEntity.status(201).body(TemplateResponse.from(
                service.create(idempotencyKey, request.toInput(), Instant.now())));
    }

    @GetMapping
    public List<TemplateResponse> list(@RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return service.list().stream().map(TemplateResponse::from).toList();
    }

    @GetMapping("/{templateId}")
    public TemplateResponse get(@PathVariable UUID templateId, @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return TemplateResponse.from(service.get(templateId));
    }

    @PostMapping("/{templateId}/revisions")
    public RevisionResponse createRevision(@PathVariable UUID templateId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId,
            @RequestBody RevisionRequest request) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        if (request == null) throw new IllegalArgumentException("request body is required");
        return RevisionResponse.from(service.createRevision(templateId, request.specJson(), actor(request.actor()),
                idempotencyKey));
    }

    @GetMapping("/{templateId}/revisions")
    public List<RevisionResponse> revisions(@PathVariable UUID templateId,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return service.revisions(templateId).stream().map(RevisionResponse::from).toList();
    }

    @GetMapping("/{templateId}/revisions/{revision}")
    public RevisionResponse revision(@PathVariable UUID templateId, @PathVariable long revision,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return RevisionResponse.from(service.revision(templateId, revision));
    }

    @PostMapping("/{templateId}/revisions/{revision}/review")
    public RevisionResponse review(@PathVariable UUID templateId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody VersionRequest request, @RequestParam(required = false) String projectId) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        return RevisionResponse.from(service.review(templateId, revision, expectedVersion(request), idempotencyKey));
    }

    @PostMapping("/{templateId}/revisions/{revision}/publish")
    public RevisionResponse publish(@PathVariable UUID templateId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody VersionRequest request, @RequestParam(required = false) String projectId) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        return RevisionResponse.from(service.publish(templateId, revision, expectedVersion(request), idempotencyKey));
    }

    @PostMapping("/{templateId}/revisions/{revision}/instances")
    public InstanceResponse instantiate(@PathVariable UUID templateId, @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        return InstanceResponse.from(service.instantiate(templateId, revision, idempotencyKey));
    }

    @GetMapping("/{templateId}/instances")
    public List<InstanceResponse> instances(@PathVariable UUID templateId,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return service.instances(templateId).stream().map(InstanceResponse::from).toList();
    }

    @GetMapping("/{templateId}/instances/{instanceId}")
    public InstanceResponse instance(@PathVariable UUID templateId, @PathVariable UUID instanceId,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return InstanceResponse.from(service.instance(templateId, instanceId));
    }

    @PostMapping("/{templateId}/instances/{instanceId}/upgrade/{revision}")
    public InstanceResponse upgrade(@PathVariable UUID templateId, @PathVariable UUID instanceId,
            @PathVariable long revision,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId) {
        requireKey(idempotencyKey);
        requireProjectScope(projectId);
        return InstanceResponse.from(service.upgrade(templateId, instanceId, revision, idempotencyKey));
    }

    public record CreateTemplateRequest(String name, String displayName, WorkerType workerType) {
        WorkerTemplateService.CreateInput toInput() {
            return new WorkerTemplateService.CreateInput(name, displayName, workerType);
        }
    }

    public record RevisionRequest(String specJson, String actor) { }
    public record VersionRequest(long expectedVersion) { }

    public record TemplateResponse(UUID id, String tenantId, String projectId, String name, String displayName,
            WorkerType workerType, Long currentPublishedRevision, long version, Instant createdAt, Instant updatedAt) {
        static TemplateResponse from(WorkerTemplate value) {
            return new TemplateResponse(value.id(), value.tenantId(), value.projectId(), value.name(), value.displayName(),
                    value.workerType(), value.currentPublishedRevision(), value.version(), value.createdAt(), value.updatedAt());
        }
    }

    public record RevisionResponse(UUID templateId, long revision, String specJson, String digest, String status,
            WorkerType workerType, String createdBy, Instant createdAt, Instant updatedAt, long version) {
        static RevisionResponse from(WorkerTemplateRevision value) {
            return new RevisionResponse(value.templateId(), value.revision(), value.specJson(), value.digest(),
                    value.status().name(), value.workerType(), value.createdBy(), value.createdAt(), value.updatedAt(), value.version());
        }
    }

    public record InstanceResponse(UUID id, UUID templateId, long templateRevision, UUID agentSpecId, UUID workerId,
            String status, long currentTemplateRevision, String idempotencyKey, Instant createdAt, Instant updatedAt,
            long version) {
        static InstanceResponse from(WorkerTemplateInstance value) {
            return new InstanceResponse(value.id(), value.templateId(), value.templateRevision(), value.agentSpecId(),
                    value.workerId(), value.status(), value.currentTemplateRevision(), value.idempotencyKey(),
                    value.createdAt(), value.updatedAt(), value.version());
        }
    }

    private static long expectedVersion(VersionRequest request) {
        if (request == null || request.expectedVersion() < 0) throw new IllegalArgumentException("expectedVersion is required");
        return request.expectedVersion();
    }

    private static String actor(String value) { return value == null || value.isBlank() ? "api" : value.trim(); }

    private void requireProjectScope(String projectId) {
        if (projectId != null && !projectId.isBlank()) service.requireProjectScope(projectId);
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        if (value.length() > 255) throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
    }
}
