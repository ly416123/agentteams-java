package io.agentteams.controlplane.artifact;

import java.net.URL;
import java.util.Objects;
import java.util.UUID;

public record ArtifactUpload(UUID taskId, UUID attemptId, String name, String storageKey,
        URL uploadUrl, URL downloadUrl) {
    public ArtifactUpload {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(uploadUrl, "uploadUrl");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
    }
}
