package io.agentteams.application.api;

import java.time.Instant;

/** JSON contract used by the internal Gateway-to-Control-Plane artifact upload bridge. */
public final class ArtifactUploadHttp {
    private ArtifactUploadHttp() {
    }

    public record PrepareRequest(String taskId, String attemptId, String agentId, String name,
            String contentType, long expirySeconds, Instant deadline) {
    }

    public record PrepareResponse(String storageKey, String uploadUrl, String downloadUrl) {
    }

    public record CompleteRequest(String taskId, String attemptId, String agentId, String name,
            String storageKey, String contentType, long sizeBytes, String sha256, Instant deadline) {
    }

    public record CompleteResponse(String artifactId, String storageKey) {
    }
}
