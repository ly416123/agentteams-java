package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.worker.WorkerOperation;
import io.agentteams.controlplane.worker.WorkerOperationService;
import io.agentteams.controlplane.worker.WorkerRolloutRequest;
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
            @RequestBody CreateAgentRequest request) {
        requireRequest(request);
        requireIdempotencyKey(idempotencyKey);
        PrincipalContext.requireScope(request.metadata() == null ? null : request.metadata().toString());
        AgentRecord agent = service.create(idempotencyKey, request.toServiceInput());
        return ResponseEntity.status(201).body(AgentResponse.from(agent));
    }

    @GetMapping("/{id}")
    public AgentResponse get(@PathVariable UUID id) {
        AgentRecord agent = service.get(id);
        PrincipalContext.requireScope(agent.metadataJson());
        return AgentResponse.from(agent);
    }

    @PostMapping("/{id}/operations/drain")
    public ResponseEntity<WorkerOperationResponse> requestDrain(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody LifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        WorkerOperation operation = operations().drain(id, expectedVersion(request), idempotencyKey);
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @PostMapping("/{id}/operations/terminate")
    public ResponseEntity<WorkerOperationResponse> requestTerminate(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody LifecycleRequest request) {
        requireIdempotencyKey(idempotencyKey);
        WorkerOperation operation = operations().terminate(id, expectedVersion(request), idempotencyKey);
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @PostMapping("/{id}/operations/rollout")
    public ResponseEntity<WorkerOperationResponse> requestRollout(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @RequestBody RolloutRequest request) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        WorkerOperation operation = operations().rollout(id, request.toServiceRequest(idempotencyKey));
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    public record LifecycleRequest(Long expectedVersion) {
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

    public record CreateAgentRequest(String name, String runtime, JsonNode capabilities, JsonNode metadata) {

        AgentService.AgentInput toServiceInput() {
            return new AgentService.AgentInput(name, runtime, json(capabilities), json(metadata));
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

    public record AgentResponse(UUID id, String name, String phase, String runtime,
            Instant createdAt, Instant updatedAt, long version) {

        static AgentResponse from(AgentRecord agent) {
            return new AgentResponse(agent.id(), agent.name(), agent.phase().name(), agent.runtime(),
                    agent.createdAt(), agent.updatedAt(), agent.version());
        }
    }

    public record WorkerOperationResponse(UUID id, UUID agentId, String type, String status,
            String requestedSpecDigest, String correlationId, Instant createdAt, Instant updatedAt, long version) {

        static WorkerOperationResponse from(WorkerOperation operation) {
            return new WorkerOperationResponse(operation.id(), operation.agentId(), operation.type().name(),
                    operation.status().name(), operation.requestedSpecDigest(), operation.correlationId(),
                    operation.createdAt(), operation.updatedAt(), operation.version());
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

    private WorkerOperationService operations() {
        if (workerOperations == null) {
            throw new IllegalStateException("worker operation service is not configured");
        }
        return workerOperations;
    }
}
