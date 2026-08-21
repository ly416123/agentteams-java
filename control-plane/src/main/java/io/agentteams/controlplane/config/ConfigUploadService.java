package io.agentteams.controlplane.config;

import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.artifact.ObjectStoragePaths;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.storage.ObjectStorage;
import java.net.URL;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Manages direct-to-object-storage config file uploads and their safe cleanup. */
public final class ConfigUploadService {
    private final FoundationPersistenceService persistence;
    private final ConfigSnapshotRepository snapshots;
    private final ObjectStorage storage;
    private final ArtifactService verification;
    private final Clock clock;

    public ConfigUploadService(FoundationPersistenceService persistence, ConfigSnapshotRepository snapshots,
            ObjectStorage storage, ArtifactService verification, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.verification = Objects.requireNonNull(verification, "verification");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PreparedUpload prepare(UUID snapshotId, String path, String contentType, String checksum,
            long sizeBytes, Duration expiry) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        snapshots.findById(snapshotId).orElseThrow(() -> new IllegalArgumentException("config snapshot does not exist"));
        requireText(contentType, "contentType");
        requireText(checksum, "checksum");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must not be negative");
        if (expiry == null || expiry.isZero() || expiry.isNegative()) throw new IllegalArgumentException("expiry must be positive");
        Instant now = clock.instant();
        ConfigUploadRecord candidate = new ConfigUploadRecord(UUID.randomUUID(), snapshotId, path,
                ObjectStoragePaths.configFile(snapshotId, path), contentType, checksum, sizeBytes, "PENDING",
                now, now.plus(expiry), null, null);
        ConfigUploadRecord upload = persistence.inTransaction(tx -> {
            if (tx.configLifecycle().insertUpload(candidate)) return candidate;
            ConfigUploadRecord existing = tx.configLifecycle().findUploadBySnapshotAndPath(snapshotId, path)
                    .orElseThrow(() -> new IllegalStateException("existing config upload is missing"));
            throw new IllegalStateException("config file path already has an upload: " + existing.status());
        });
        return new PreparedUpload(upload, storage.presignPut(upload.storageKey(), upload.contentType(), expiry));
    }

    public ConfigFileRecord complete(UUID snapshotId, UUID uploadId) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        ConfigUploadRecord upload = persistence.inTransaction(tx -> tx.configLifecycle().findUpload(uploadId))
                .orElseThrow(() -> new IllegalArgumentException("config upload does not exist"));
        if (!snapshotId.equals(upload.snapshotId())) {
            throw new IllegalArgumentException("config upload does not belong to snapshot");
        }
        if (!upload.pending()) {
            throw new IllegalStateException("config upload is not pending");
        }
        if (clock.instant().isAfter(upload.expiresAt())) {
            throw new IllegalStateException("config upload has expired");
        }
        ArtifactService.ArtifactVerification verified = verification.verifyUploadedObject(upload.storageKey(),
                upload.expectedChecksum(), upload.expectedSizeBytes());
        ConfigFileRecord file = new ConfigFileRecord(UUID.randomUUID(), upload.snapshotId(), upload.path(),
                upload.storageKey(), verified.sha256(), verified.sizeBytes(), upload.contentType());
        Instant now = clock.instant();
        return persistence.inTransaction(tx -> {
            tx.configLifecycle().insertFile(file);
            tx.configLifecycle().markUploadCompleted(upload.id(), now);
            return file;
        });
    }

    public ConfigFileDownload downloadCompleted(UUID snapshotId, String path) {
        Objects.requireNonNull(snapshotId, "snapshotId");
        requireText(path, "path");
        ConfigFileRecord file = persistence.inTransaction(tx -> tx.configLifecycle().findFile(snapshotId, path))
                .orElseThrow(() -> new IllegalArgumentException("completed config file does not exist"));
        return new ConfigFileDownload(file, storage.download(file.storageKey()));
    }

    public int cleanupExpired(int limit) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        Instant now = clock.instant();
        var expired = persistence.inTransaction(tx -> tx.configLifecycle().findExpiredUploads(now, limit));
        int deleted = 0;
        for (ConfigUploadRecord upload : expired) {
            storage.delete(upload.storageKey());
            persistence.inTransaction(tx -> {
                tx.configLifecycle().markUploadDeleted(upload.id(), now);
                return null;
            });
            deleted++;
        }
        return deleted;
    }

    public record PreparedUpload(ConfigUploadRecord upload, URL uploadUrl) {
        public PreparedUpload {
            Objects.requireNonNull(upload, "upload");
            Objects.requireNonNull(uploadUrl, "uploadUrl");
        }
    }

    public record ConfigFileDownload(ConfigFileRecord file, InputStream content) {
        public ConfigFileDownload {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(content, "content");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
