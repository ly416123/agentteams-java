package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigBindingStatus;
import io.agentteams.controlplane.config.ConfigApplyRecord;
import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/config")
public final class ConfigController {
    private final ConfigSnapshotService snapshots;
    private final ConfigSnapshotRepository snapshotRepository;
    private final ConfigDeploymentService deployments;

    public ConfigController(ConfigSnapshotService snapshots, ConfigSnapshotRepository snapshotRepository,
            ConfigDeploymentService deployments) {
        this.snapshots = snapshots;
        this.snapshotRepository = snapshotRepository;
        this.deployments = deployments;
    }

    @PostMapping("/snapshots")
    public ResponseEntity<SnapshotResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateSnapshotRequest request) {
        if (request == null || request.subject() == null || request.subject().isBlank()
                || request.manifest() == null || !request.manifest().isObject()) {
            throw new IllegalArgumentException("subject and object manifest are required");
        }
        PrincipalContext.requireScope(request.manifest().toString());
        String actor = PrincipalContext.actorOr(request.actor());
        requireKey(idempotencyKey);
        ConfigSnapshot snapshot = snapshots.create(request.subject(), request.manifest().toString(), actor, idempotencyKey);
        return ResponseEntity.status(201).body(SnapshotResponse.from(snapshot));
    }

    @PostMapping("/snapshots/{snapshotId}/agents/{agentId}")
    public DeploymentResponse deploy(@PathVariable UUID snapshotId, @PathVariable UUID agentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        ConfigSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
        PrincipalContext.requireScope(snapshot.manifestJson());
        ConfigDeploymentService.ConfigDeployment deployment = deployments.deploy(agentId, snapshot.subject(), snapshot,
                idempotencyKey);
        return DeploymentResponse.from(deployment);
    }

    @GetMapping("/bindings/{bindingId}")
    public BindingStatusResponse bindingStatus(@PathVariable UUID bindingId) {
        ConfigBindingStatus status = deployments.findBindingStatus(bindingId)
                .orElseThrow(() -> new ResourceNotFoundException("config binding", bindingId));
        PrincipalContext.requireScope(status.desiredSnapshot().manifestJson());
        return BindingStatusResponse.from(status);
    }

    @PostMapping("/bindings/{bindingId}/retry")
    public DeploymentResponse retry(@PathVariable UUID bindingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        return DeploymentResponse.from(deployments.retry(bindingId, idempotencyKey));
    }

    @PostMapping("/bindings/{bindingId}/rollback")
    public DeploymentResponse rollback(@PathVariable UUID bindingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireKey(idempotencyKey);
        return DeploymentResponse.from(deployments.rollback(bindingId, idempotencyKey));
    }

    @GetMapping(value = "/snapshots/{snapshotId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> manifest(@PathVariable UUID snapshotId) {
        ConfigSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
        PrincipalContext.requireScope(snapshot.manifestJson());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(snapshot.manifestJson());
    }

    public record CreateSnapshotRequest(String subject, JsonNode manifest, String actor) {
    }

    public record SnapshotResponse(UUID id, String subject, long version, String checksum,
            String actor, Instant createdAt) {
        static SnapshotResponse from(ConfigSnapshot snapshot) {
            return new SnapshotResponse(snapshot.id(), snapshot.subject(), snapshot.version(), snapshot.checksum(),
                    snapshot.actor(), snapshot.createdAt());
        }
    }

    public record DeploymentResponse(UUID bindingId, UUID agentId, UUID snapshotId, long configVersion,
            UUID eventId, String phase) {
        static DeploymentResponse from(ConfigDeploymentService.ConfigDeployment deployment) {
            return new DeploymentResponse(deployment.binding().id(), deployment.binding().agentId(),
                    deployment.snapshot().id(), deployment.snapshot().version(), deployment.eventId(), "PENDING");
        }
    }

    public record BindingStatusResponse(BindingResponse binding, SnapshotResponse desiredSnapshot,
            ApplyResponse apply) {
        static BindingStatusResponse from(ConfigBindingStatus status) {
            return new BindingStatusResponse(BindingResponse.from(status.binding()),
                    SnapshotResponse.from(status.desiredSnapshot()), ApplyResponse.from(status.apply()));
        }
    }

    public record BindingResponse(UUID id, String subject, UUID agentId, UUID snapshotId, Instant desiredAt) {
        static BindingResponse from(ConfigBindingRecord binding) {
            return new BindingResponse(binding.id(), binding.subject(), binding.agentId(), binding.snapshotId(),
                    binding.desiredAt());
        }
    }

    public record ApplyResponse(String phase, String error, Instant appliedAt, Long observedRevision,
            String failureCode, boolean rollback) {
        static ApplyResponse from(ConfigApplyRecord apply) {
            return apply == null ? null : new ApplyResponse(apply.phase(), apply.errorMessage(), apply.appliedAt(),
                    apply.observedVersion(), apply.failureCode(), apply.rollback());
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    }
}
