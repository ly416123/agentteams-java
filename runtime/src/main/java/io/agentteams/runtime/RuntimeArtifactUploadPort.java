package io.agentteams.runtime;

/**
 * Runtime artifact upload boundary. Implementations move artifact bytes to
 * durable object storage out of band; the runtime only references the stored
 * object. Applications may inject a transport-backed implementation or none,
 * in which case artifacts degrade to the in-process memory reference.
 */
public interface RuntimeArtifactUploadPort {

    /**
     * Stores the payload and returns the durable object reference. Implementations
     * must throw on any failure so the caller can fall back.
     */
    UploadedArtifact upload(String taskId, String attemptId, String name, String contentType, byte[] payload);

    record UploadedArtifact(String storageKey, String downloadUrl) {
        public UploadedArtifact {
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalArgumentException("storageKey must not be blank");
            }
        }
    }
}
