package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Metadata-only Artifact catalog; downloads remain behind the task/attempt authorization boundary. */
@RestController
@RequestMapping("/api/v1/artifacts")
public final class ArtifactManagementController {
    private final FoundationPersistenceService persistence;
    private final ResourceScopeRepository scopes;

    public ArtifactManagementController(FoundationPersistenceService persistence, ResourceScopeRepository scopes) {
        this.persistence = persistence;
        this.scopes = scopes;
    }

    @GetMapping
    public List<ArtifactResponse> list(@RequestParam(defaultValue = "100") int limit) {
        return persistence.inTransaction(tx -> tx.artifacts().findLatest(limit)).stream()
                .filter(record -> scopes.visible("TASK", record.taskId()))
                .map(ArtifactResponse::from).toList();
    }

    public record ArtifactResponse(UUID id, UUID taskId, UUID attemptId, String name, String contentType,
            long sizeBytes, String sha256, String status, String metadata, Instant createdAt, long version) {
        static ArtifactResponse from(ArtifactRecord value) {
            return new ArtifactResponse(value.id(), value.taskId(), value.attemptId(), value.name(),
                    value.contentType(), value.sizeBytes(), value.sha256(), value.status(), value.metadataJson(),
                    value.createdAt(), value.version());
        }
    }
}
