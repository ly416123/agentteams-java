package io.agentteams.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentteams.controlplane.artifact.ArtifactCompletionService;
import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.artifact.ArtifactUpload;
import io.agentteams.controlplane.artifact.ObjectStoragePaths;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.TaskService;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;

/** HTTP boundary for direct-to-object-storage task artifact uploads. */
@RestController
@ConditionalOnBean({ArtifactService.class, ArtifactCompletionService.class})
@RequestMapping("/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts")
public final class ArtifactController {
    private static final long DEFAULT_EXPIRY_SECONDS = 900;
    private static final long MAX_EXPIRY_SECONDS = 3600;

    private final ArtifactService artifacts;
    private final ArtifactCompletionService completion;
    private final FoundationPersistenceService persistence;
    private final TaskService tasks;
    private final ResourceScopeRepository resourceScopes;

    public ArtifactController(ArtifactService artifacts, ArtifactCompletionService completion,
            FoundationPersistenceService persistence, TaskService tasks) {
        this(artifacts, completion, persistence, tasks, (ResourceScopeRepository) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtifactController(ArtifactService artifacts, ArtifactCompletionService completion,
            FoundationPersistenceService persistence, TaskService tasks,
            ObjectProvider<ResourceScopeRepository> scopes) {
        this(artifacts, completion, persistence, tasks, scopes.getIfAvailable());
    }

    ArtifactController(ArtifactService artifacts, ArtifactCompletionService completion,
            FoundationPersistenceService persistence, TaskService tasks,
            ResourceScopeRepository resourceScopes) {
        this.artifacts = artifacts;
        this.completion = completion;
        this.persistence = persistence;
        this.tasks = tasks;
        this.resourceScopes = resourceScopes;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public List<ArtifactResponse> list(@PathVariable UUID taskId, @PathVariable UUID attemptId) {
        requireArtifactScope(taskId, attemptId);
        return persistence.findArtifactsByTaskIdAndAttemptId(taskId, attemptId).stream()
                .filter(artifact -> visible(artifact.id()))
                .map(this::response).toList();
    }

    @org.springframework.web.bind.annotation.GetMapping("/{artifactId}")
    public ArtifactResponse get(@PathVariable UUID taskId, @PathVariable UUID attemptId,
            @PathVariable UUID artifactId) {
        requireArtifactScope(taskId, attemptId);
        ArtifactRecord record = persistence.findArtifact(artifactId)
                .filter(artifact -> taskId.equals(artifact.taskId()) && attemptId.equals(artifact.attemptId()))
                .orElseThrow(() -> new ResourceNotFoundException("artifact", artifactId));
        requireVisible(record.id());
        return response(record);
    }

    @PostMapping("/uploads")
    public ResponseEntity<PrepareResponse> prepare(@PathVariable UUID taskId, @PathVariable UUID attemptId,
            @RequestBody PrepareRequest request) {
        requireArtifactScope(taskId, attemptId);
        if (request == null || request.name() == null || request.name().isBlank()
                || request.contentType() == null || request.contentType().isBlank()) {
            throw new IllegalArgumentException("artifact name and contentType are required");
        }
        long expirySeconds = request.expirySeconds() <= 0 ? DEFAULT_EXPIRY_SECONDS
                : Math.min(request.expirySeconds(), MAX_EXPIRY_SECONDS);
        ArtifactUpload upload = artifacts.prepareUpload(taskId, attemptId, request.name(), request.contentType(),
                Duration.ofSeconds(expirySeconds));
        return ResponseEntity.ok(PrepareResponse.from(upload));
    }

    @PostMapping("/complete")
    public ResponseEntity<CompleteResponse> complete(@PathVariable UUID taskId, @PathVariable UUID attemptId,
            @RequestBody CompleteRequest request) {
        requireArtifactScope(taskId, attemptId);
        if (request == null) {
            throw new IllegalArgumentException("artifact completion request is required");
        }
        String expectedStorageKey = ObjectStoragePaths.artifact(taskId, attemptId, request.name());
        if (!expectedStorageKey.equals(request.storageKey())) {
            throw new IllegalArgumentException("artifact storage key does not match task and attempt");
        }
        ArtifactRecord record = completion.complete(new ArtifactCompletionService.CompletionRequest(
                taskId, attemptId, request.name(), request.storageKey(), request.contentType(), request.sizeBytes(),
                request.sha256(), request.metadata() == null ? "{}" : request.metadata().toString()));
        bindIfAuthenticated(record.id());
        requireVisible(record.id());
        return ResponseEntity.ok(CompleteResponse.from(record));
    }

    public record PrepareRequest(String name, String contentType, long expirySeconds) {
    }

    public record PrepareResponse(UUID taskId, UUID attemptId, String name, String storageKey,
            URL uploadUrl, URL downloadUrl) {
        static PrepareResponse from(ArtifactUpload upload) {
            return new PrepareResponse(upload.taskId(), upload.attemptId(), upload.name(), upload.storageKey(),
                    upload.uploadUrl(), upload.downloadUrl());
        }
    }

    public record CompleteRequest(String name, String storageKey, String contentType, long sizeBytes,
            String sha256, JsonNode metadata) {
    }

    public record CompleteResponse(UUID id, UUID taskId, UUID attemptId, String name, String storageKey,
            String contentType, long sizeBytes, String sha256, String status, Instant createdAt) {
        static CompleteResponse from(ArtifactRecord record) {
            return new CompleteResponse(record.id(), record.taskId(), record.attemptId(), record.name(),
                    record.storageKey(), record.contentType(), record.sizeBytes(), record.sha256(), record.status(),
                    record.createdAt());
        }
    }

    public record ArtifactResponse(UUID id, UUID taskId, UUID attemptId, String name, String storageKey,
            String contentType, long sizeBytes, String sha256, String status, URL downloadUrl, Instant createdAt) {
    }

    private ArtifactResponse response(ArtifactRecord record) {
        return new ArtifactResponse(record.id(), record.taskId(), record.attemptId(), record.name(),
                record.storageKey(), record.contentType(), record.sizeBytes(), record.sha256(), record.status(),
                artifacts.prepareDownload(record.storageKey(), Duration.ofMinutes(15)), record.createdAt());
    }

    private void requireArtifactScope(UUID taskId, UUID attemptId) {
        TaskAttemptRecord attempt = persistence.findTaskAttempt(attemptId)
                .filter(candidate -> taskId.equals(candidate.taskId()))
                .orElseThrow(() -> new ResourceNotFoundException("task attempt", attemptId));
        if (PrincipalContext.current().isPresent()) {
            tasks.get(taskId);
        }
    }

    private boolean visible(UUID artifactId) {
        return resourceScopes == null || PrincipalContext.current().isEmpty()
                || resourceScopes.visible("ARTIFACT", artifactId);
    }

    private void requireVisible(UUID artifactId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("ARTIFACT", artifactId);
        }
    }

    private void bindIfAuthenticated(UUID artifactId) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("ARTIFACT", artifactId, principal, Instant.now()));
        }
    }
}
