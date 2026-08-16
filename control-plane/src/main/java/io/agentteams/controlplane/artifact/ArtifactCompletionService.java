package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Verifies direct-to-object-storage uploads before durably exposing artifact metadata. */
public final class ArtifactCompletionService {
    private final FoundationPersistenceService persistence;
    private final ArtifactService artifacts;
    private final Clock clock;

    public ArtifactCompletionService(FoundationPersistenceService persistence, ArtifactService artifacts, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ArtifactRecord complete(CompletionRequest request) {
        Objects.requireNonNull(request, "request");
        ArtifactService.ArtifactVerification verified = artifacts.verifyUploadedObject(
                request.storageKey(), request.sha256(), request.sizeBytes());
        Instant now = clock.instant();
        ArtifactRecord candidate = new ArtifactRecord(UUID.randomUUID(), request.taskId(), request.attemptId(),
                request.name(), request.storageKey(), request.contentType(), verified.sizeBytes(), verified.sha256(),
                "AVAILABLE", request.metadataJson(), now, now, 0);
        return persistence.inTransaction(tx -> {
            if (tx.artifacts().insertIfAbsent(candidate)) return candidate;
            return tx.artifacts().findByAttemptIdNameSha256(request.attemptId(), request.name(), verified.sha256())
                    .orElseThrow(() -> new IllegalStateException("duplicate artifact metadata is missing"));
        });
    }

    public record CompletionRequest(UUID taskId, UUID attemptId, String name, String storageKey,
            String contentType, long sizeBytes, String sha256, String metadataJson) {
        public CompletionRequest {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(attemptId, "attemptId");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (storageKey == null || storageKey.isBlank()) throw new IllegalArgumentException("storageKey is required");
            if (contentType == null || contentType.isBlank()) throw new IllegalArgumentException("contentType is required");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
            if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 is required");
            metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
        }
    }
}
