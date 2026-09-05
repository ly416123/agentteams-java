package io.agentteams.controlplane.config;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.storage.ObjectStorage;
import java.util.Objects;
import java.util.UUID;

/** Deletes unreferenced old configuration snapshots only after their objects are gone. */
public final class ConfigSnapshotCleanupService {
    private final FoundationPersistenceService persistence;
    private final ObjectStorage storage;

    public ConfigSnapshotCleanupService(FoundationPersistenceService persistence, ObjectStorage storage) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public int cleanup(int keepCount, int batchSize) {
        if (keepCount <= 0) throw new IllegalArgumentException("keepCount must be positive");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        var candidates = persistence.inTransaction(tx -> tx.configLifecycle()
                .findCleanupSnapshotIds(keepCount, batchSize));
        int deleted = 0;
        for (UUID snapshotId : candidates) {
            var keys = persistence.inTransaction(tx -> tx.configLifecycle().findObjectKeys(snapshotId));
            try {
                for (String key : keys) storage.delete(key);
                persistence.inTransaction(tx -> {
                    tx.configLifecycle().deleteSnapshot(snapshotId);
                    return null;
                });
                deleted++;
            } catch (RuntimeException ignored) {
                // Keep the database rows so a later run can retry object deletion safely.
            }
        }
        return deleted;
    }
}
