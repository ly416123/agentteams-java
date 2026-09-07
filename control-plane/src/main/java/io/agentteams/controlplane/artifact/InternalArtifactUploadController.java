package io.agentteams.controlplane.artifact;

import io.agentteams.application.api.ArtifactUploadHttp;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal HTTP bridge for the separately deployed Gateway artifact upload
 * service. Artifact bytes flow straight from the Worker to object storage via
 * presigned URLs; the Control Plane only brokers these two metadata calls and
 * verifies the stored object before exposing artifact metadata.
 *
 * <p>Attempt ownership is enforced here: the reported agent id must match the
 * attempt actor, so a Worker can only upload artifacts for assignments it
 * actually holds.</p>
 */
@RestController
@ConditionalOnBean({ArtifactService.class, ArtifactCompletionService.class})
@RequestMapping("/internal/v1/artifacts")
public final class InternalArtifactUploadController {
    static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";
    private static final long DEFAULT_EXPIRY_SECONDS = 900;
    private static final long MAX_EXPIRY_SECONDS = 3600;

    private final ArtifactService artifacts;
    private final ArtifactCompletionService completion;
    private final FoundationPersistenceService persistence;
    private final String internalToken;

    public InternalArtifactUploadController(ArtifactService artifacts, ArtifactCompletionService completion,
            FoundationPersistenceService persistence,
            @Value("${agentteams.quota.internal-token:}") String internalToken) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/uploads")
    public ResponseEntity<ArtifactUploadHttp.PrepareResponse> prepare(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody ArtifactUploadHttp.PrepareRequest request) {
        authorize(token);
        OwnedAttempt owned = requireOwnedAttempt(request.taskId(), request.attemptId(), request.agentId());
        if (request.name() == null || request.name().isBlank()
                || request.contentType() == null || request.contentType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "artifact name and contentType are required");
        }
        long expirySeconds = request.expirySeconds() <= 0 ? DEFAULT_EXPIRY_SECONDS
                : Math.min(request.expirySeconds(), MAX_EXPIRY_SECONDS);
        ArtifactUpload upload = artifacts.prepareUpload(owned.taskId(), owned.attemptId(),
                request.name(), request.contentType(), Duration.ofSeconds(expirySeconds));
        return ResponseEntity.ok(new ArtifactUploadHttp.PrepareResponse(upload.storageKey(),
                upload.uploadUrl().toString(), upload.downloadUrl().toString()));
    }

    @PostMapping("/complete")
    public ResponseEntity<ArtifactUploadHttp.CompleteResponse> complete(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody ArtifactUploadHttp.CompleteRequest request) {
        authorize(token);
        OwnedAttempt owned = requireOwnedAttempt(request.taskId(), request.attemptId(), request.agentId());
        if (request.name() == null || request.name().isBlank()
                || request.contentType() == null || request.contentType().isBlank()
                || request.sha256() == null || request.sha256().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "artifact name, contentType and sha256 are required");
        }
        String expectedStorageKey = ObjectStoragePaths.artifact(owned.taskId(), owned.attemptId(),
                request.name());
        if (!expectedStorageKey.equals(request.storageKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "artifact storage key does not match task and attempt");
        }
        ArtifactRecord record = completion.complete(new ArtifactCompletionService.CompletionRequest(
                owned.taskId(), owned.attemptId(), request.name(), request.storageKey(),
                request.contentType(), request.sizeBytes(), request.sha256(), "{}"));
        return ResponseEntity.ok(new ArtifactUploadHttp.CompleteResponse(record.id().toString(),
                record.storageKey()));
    }

    private OwnedAttempt requireOwnedAttempt(String taskId, String attemptId, String agentId) {
        UUID task = uuid(taskId, "taskId");
        UUID attempt = uuid(attemptId, "attemptId");
        if (agentId == null || agentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        TaskAttemptRecord record = persistence.findTaskAttempt(attempt)
                .filter(candidate -> task.equals(candidate.taskId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "task attempt does not exist for this task"));
        if (!agentId.equals(record.actor())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "attempt is not owned by this agent");
        }
        return new OwnedAttempt(task, attempt);
    }

    private void authorize(String token) {
        if (internalToken.isBlank() || token == null || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal artifact token rejected");
        }
    }

    private static UUID uuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a UUID");
        }
    }

    private record OwnedAttempt(UUID taskId, UUID attemptId) {
    }
}
