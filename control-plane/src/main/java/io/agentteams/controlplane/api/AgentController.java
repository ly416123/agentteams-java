package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.worker.WorkerOperation;
import io.agentteams.controlplane.worker.WorkerOperationObservation;
import io.agentteams.controlplane.worker.WorkerOperationService;
import io.agentteams.controlplane.worker.WorkerRolloutRequest;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequestMapping("/api/v1/agents")
public final class AgentController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AgentService service;
    private final WorkerOperationService workerOperations;

    @Autowired
    public AgentController(AgentService service, WorkerOperationService workerOperations) {
        this.service = service;
        this.workerOperations = workerOperations;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId,
            @RequestBody CreateAgentRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        PrincipalContext.requireScope(request.metadata() == null ? null : request.metadata().toString());
        AgentRecord agent = service.create(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(AgentResponse.from(agent));
    }

    @GetMapping
    public CursorPage<AgentResponse> list(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) String search,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return service.list(new CursorPageRequest(cursor, pageSize, sort, direction), status,
                firstNonBlank(q, search)).map(AgentResponse::from);
    }

    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable UUID id, @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        AgentRecord agent = service.get(id);
        PrincipalContext.requireScope(agent.metadataJson());
        return AgentResponse.from(agent);
    }

    @PostMapping("/{id}/operations/drain")
    public ResponseEntity<WorkerOperationResponse> requestDrain(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody LifecycleRequest request, @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        WorkerOperation operation = operations().drain(id, expectedVersion(request), idempotencyKey);
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @PostMapping("/{id}/operations/terminate")
    public ResponseEntity<WorkerOperationResponse> requestTerminate(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody LifecycleRequest request, @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        WorkerOperation operation = operations().terminate(id, expectedVersion(request), idempotencyKey);
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @PostMapping("/{id}/operations/rollout")
    public ResponseEntity<WorkerOperationResponse> requestRollout(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody RolloutRequest request, @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        WorkerOperation operation = operations().rollout(id, request.toServiceRequest(idempotencyKey));
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @GetMapping("/{agentId}/operations/{operationId}")
    public WorkerOperationResponse getOperation(@PathVariable UUID agentId, @PathVariable UUID operationId,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return WorkerOperationResponse.from(operations().get(agentId, operationId));
    }

    @GetMapping("/{agentId}/operations")
    public CursorPage<WorkerOperationListResponse> listOperations(@PathVariable UUID agentId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return operations().list(agentId, new CursorPageRequest(cursor, pageSize, sort, direction))
                .map(operation -> WorkerOperationListResponse.from(operation,
                        operations().observation(operation.id()).orElse(null)));
    }

    @PostMapping("/{agentId}/operations/{operationId}/rollback")
    public WorkerOperationResponse rollback(@PathVariable UUID agentId, @PathVariable UUID operationId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody OperationVersionRequest request, @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        return WorkerOperationResponse.from(operations().rollback(agentId, operationId,
                expectedVersion(request), idempotencyKey));
    }

    public record LifecycleRequest(Long expectedVersion) {
    }

    public record OperationVersionRequest(Long expectedVersion) {
    }

    public record RolloutRequest(Long expectedVersion, String imageDigest, String runtime,
            String configRevision, String secretGeneration, String previousStableSpec,
            String owner, String correlationId) {

        WorkerRolloutRequest toServiceRequest(String idempotencyKey) {
            if (expectedVersion == null || expectedVersion < 0) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return new WorkerRolloutRequest(expectedVersion, idempotencyKey, imageDigest, runtime,
                    configRevision, secretGeneration,
                    previousStableSpec == null || previousStableSpec.isBlank() ? "{}" : previousStableSpec,
                    owner == null || owner.isBlank() ? PrincipalContext.actorOr("control-plane") : owner,
                    correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId);
        }
    }

    public record CreateAgentRequest(String name, String runtime, WorkerType workerType,
            JsonNode capabilities, JsonNode metadata) {

        AgentService.AgentInput toServiceInput() {
            return new AgentService.AgentInput(name, runtime, workerType, json(capabilities), json(metadata));
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

    public record AgentResponse(UUID id, String name, WorkerType workerType, String phase, String runtime,
            Instant createdAt, Instant updatedAt, long version, String templateName) {

        static AgentResponse from(AgentRecord agent) {
            return new AgentResponse(agent.id(), agent.name(), agent.workerType(), agent.phase().name(), agent.runtime(),
                    agent.createdAt(), agent.updatedAt(), agent.version(), agent.templateName());
        }
    }

    public record WorkerOperationResponse(UUID id, UUID agentId, String type, String status,
            String requestedSpecDigest, String correlationId, Instant createdAt, Instant updatedAt, long version) {

        public static WorkerOperationResponse from(WorkerOperation operation) {
            return new WorkerOperationResponse(operation.id(), operation.agentId(), operation.type().name(),
                    operation.status().name(), operation.requestedSpecDigest(), operation.correlationId(),
                    operation.createdAt(), operation.updatedAt(), operation.version());
        }
    }

    public record WorkerOperationListResponse(UUID id, UUID agentId, String type, String status,
            String requestedSpecDigest, String requestedRuntime, String requestedConfigRevision,
            String failureCategory, long expectedAgentVersion, Instant createdAt, Instant updatedAt, long version,
            boolean operatorReady, String operatorSpecDigest, String operatorRuntime,
            String operatorConfigRevision, Instant operatorObservedAt, boolean gatewayOnline,
            String gatewaySpecDigest, String gatewayRuntime, String gatewayConfigRevision,
            Instant gatewayObservedAt, boolean observationsMatch) {
        static WorkerOperationListResponse from(WorkerOperation operation, WorkerOperationObservation observation) {
            boolean hasObservation = observation != null;
            return new WorkerOperationListResponse(operation.id(), operation.agentId(), operation.type().name(),
                    operation.status().name(), operation.requestedSpecDigest(), operation.requestedRuntime(),
                    operation.requestedConfigRevision(), operation.failureCategory(), operation.expectedAgentVersion(),
                    operation.createdAt(), operation.updatedAt(), operation.version(),
                    hasObservation && observation.operatorReady(), hasObservation ? observation.operatorSpecDigest() : null,
                    hasObservation ? observation.operatorRuntime() : null,
                    hasObservation ? observation.operatorConfigRevision() : null,
                    hasObservation ? observation.operatorObservedAt() : null,
                    hasObservation && observation.gatewayOnline(), hasObservation ? observation.gatewaySpecDigest() : null,
                    hasObservation ? observation.gatewayRuntime() : null,
                    hasObservation ? observation.gatewayConfigRevision() : null,
                    hasObservation ? observation.gatewayObservedAt() : null,
                    hasObservation && observation.matches(operation));
        }
    }

    private static void requireRequest(CreateAgentRequest request) {
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

    private static long expectedVersion(LifecycleRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return request.expectedVersion();
    }

    private static long expectedVersion(OperationVersionRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return request.expectedVersion();
    }

    private WorkerOperationService operations() {
        if (workerOperations == null) {
            throw new IllegalStateException("worker operation service is not configured");
        }
        return workerOperations;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private void requireProjectScope(String projectId) {
        if (projectId != null && !projectId.isBlank()) service.requireProjectScope(projectId);
    }
}
