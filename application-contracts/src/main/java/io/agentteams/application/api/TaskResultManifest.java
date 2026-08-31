package io.agentteams.application.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Final task result containing summary data and references to stored artifacts. */
public record TaskResultManifest(UUID taskId, UUID runId, String status, String summary,
        List<ArtifactMetadata> artifacts) {

    public TaskResultManifest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        status = requireText(status, "status");
        summary = requireText(summary, "summary");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }

    /** Metadata-only artifact reference; its content remains in object storage. */
    public record ArtifactMetadata(String name, String storageRef, String contentType,
            long sizeBytes, String sha256, long version, String stage,
            TaskEventVisibility visibility) {

        public ArtifactMetadata {
            name = requireText(name, "name");
            storageRef = requireText(storageRef, "storageRef");
            contentType = requireText(contentType, "contentType");
            sha256 = requireText(sha256, "sha256");
            stage = requireText(stage, "stage");
            Objects.requireNonNull(visibility, "visibility");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
