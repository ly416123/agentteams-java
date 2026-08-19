package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.security.PrincipalContext;
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
    public ResponseEntity<SnapshotResponse> create(@RequestBody CreateSnapshotRequest request) {
        if (request == null || request.subject() == null || request.subject().isBlank()
                || request.manifest() == null || !request.manifest().isObject()) {
            throw new IllegalArgumentException("subject and object manifest are required");
        }
        String actor = PrincipalContext.actorOr(request.actor());
        ConfigSnapshot snapshot = snapshots.create(request.subject(), request.manifest().toString(), actor);
        return ResponseEntity.status(201).body(SnapshotResponse.from(snapshot));
    }

    @PostMapping("/snapshots/{snapshotId}/agents/{agentId}")
    public DeploymentResponse deploy(@PathVariable UUID snapshotId, @PathVariable UUID agentId) {
        ConfigSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
        ConfigDeploymentService.ConfigDeployment deployment = deployments.deploy(agentId, snapshot.subject(), snapshot);
        return DeploymentResponse.from(deployment);
    }

    @GetMapping(value = "/snapshots/{snapshotId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> manifest(@PathVariable UUID snapshotId) {
        ConfigSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
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
}
