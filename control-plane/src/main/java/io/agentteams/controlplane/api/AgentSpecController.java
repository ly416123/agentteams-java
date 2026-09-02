package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.agentspec.AgentSpecRecord;
import io.agentteams.controlplane.agentspec.AgentSpecDeploymentService;
import io.agentteams.controlplane.agentspec.AgentSpecService;
import io.agentteams.controlplane.security.PrincipalContext;
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
@RequestMapping("/api/v1/agent-specs")
public final class AgentSpecController {

    @PostMapping
    public ResponseEntity<AgentSpecResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId,
            @RequestBody CreateAgentSpecRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        AgentSpecRecord record = service.create(idempotencyKey, request.toInput());
        return ResponseEntity.status(201).body(AgentSpecResponse.from(record));
    }

    @GetMapping
    public List<AgentSpecResponse> list(@RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return service.list().stream().map(AgentSpecResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AgentSpecResponse get(@PathVariable UUID id, @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        return AgentSpecResponse.from(service.get(id));
    }

    @PostMapping("/{id}/publish")
    public AgentSpecResponse publish(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        return AgentSpecResponse.from(service.publish(idempotencyKey, id));
    }

    @PostMapping("/{id}/deactivate")
    public AgentSpecResponse deactivate(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) String projectId) {
        requireIdempotencyKey(idempotencyKey);
        requireProjectScope(projectId);
        return AgentSpecResponse.from(service.deactivate(idempotencyKey, id));
    }

    @PostMapping("/{id}/deployments/{agentId}")
    public DeploymentResponse deploy(@PathVariable UUID id, @PathVariable UUID agentId,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestParam(required = false) String projectId) {
        requireProjectScope(projectId);
        AgentSpecDeploymentService.AgentSpecDeployment deployment = deployments.deploy(id, agentId,
                PrincipalContext.actorOr(actor));
        return DeploymentResponse.from(deployment);
    }

    private final AgentSpecService service;
    private final AgentSpecDeploymentService deployments;

    public AgentSpecController(AgentSpecService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentSpecController(AgentSpecService service, AgentSpecDeploymentService deployments) {
        this.service = service;
        this.deployments = deployments;
    }

    public record CreateAgentSpecRequest(String name, String runtime, String modelProvider,
            String modelName, String teamRef, String desiredState, JsonNode spec) {
        AgentSpecService.Input toInput() {
            return new AgentSpecService.Input(name, runtime, modelProvider, modelName, teamRef, desiredState,
                    spec == null || spec.isNull() ? "{}" : spec.toString());
        }
    }

    public record AgentSpecResponse(UUID id, String name, String runtime, String modelProvider,
            String modelName, String teamRef, String desiredState, String lifecycleStatus,
            String spec, Instant createdAt, Instant updatedAt, long version, String tenantId, String projectId) {
        static AgentSpecResponse from(AgentSpecRecord record) {
            return new AgentSpecResponse(record.id(), record.name(), record.runtime(), record.modelProvider(),
                    record.modelName(), record.teamRef(), record.desiredState(), record.lifecycleStatus(),
                    record.specJson(), record.createdAt(), record.updatedAt(), record.version(), record.tenantId(),
                    record.projectId());
        }
    }

    public record DeploymentResponse(UUID bindingId, UUID agentId, UUID snapshotId, long configVersion,
            UUID eventId, String phase) {
        static DeploymentResponse from(AgentSpecDeploymentService.AgentSpecDeployment deployment) {
            var config = deployment.deployment();
            return new DeploymentResponse(config.binding().id(), config.binding().agentId(),
                    config.snapshot().id(), config.snapshot().version(), config.eventId(), "PENDING");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        if (value.length() > 255) throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
    }

    private void requireProjectScope(String projectId) {
        if (projectId != null && !projectId.isBlank()) service.requireProjectScope(projectId);
    }
}
