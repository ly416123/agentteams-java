package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.storage.ObjectStorage;
import java.time.Duration;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

public final class ArtifactService {
    private final ObjectStorage storage;

    public ArtifactService(ObjectStorage storage) { this.storage = Objects.requireNonNull(storage, "storage"); }

    public ArtifactUpload prepareUpload(UUID taskId, UUID attemptId, String name, String contentType,
            Duration expiry) {
        String key = ObjectStoragePaths.artifact(taskId, attemptId, name);
        return new ArtifactUpload(taskId, attemptId, name, key,
                storage.presignPut(key, contentType, expiry), storage.presignGet(key, expiry));
    }

    public java.net.URL prepareDownload(String storageKey, Duration expiry) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        return storage.presignGet(storageKey, expiry);
    }

    public ArtifactVerification verifyUploadedObject(String storageKey, String expectedSha256, long expectedSize) {
        if (storageKey == null || storageKey.isBlank()) throw new IllegalArgumentException("storageKey is required");
        if (expectedSha256 == null || expectedSha256.isBlank()) throw new IllegalArgumentException("expectedSha256 is required");
        if (expectedSize < 0) throw new IllegalArgumentException("expectedSize must not be negative");
        try (InputStream input = storage.download(storageKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
            String actual = java.util.HexFormat.of().formatHex(digest.digest());
            if (size != expectedSize || !actual.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalArgumentException("artifact checksum or size mismatch");
            }
            return new ArtifactVerification(storageKey, size, actual);
        } catch (IOException error) {
            throw new IllegalStateException("artifact verification failed", error);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record ArtifactVerification(String storageKey, long sizeBytes, String sha256) { }
}
