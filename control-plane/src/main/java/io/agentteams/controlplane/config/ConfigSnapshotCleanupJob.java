package io.agentteams.controlplane.config;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically applies the configured snapshot retention policy. */
public final class ConfigSnapshotCleanupJob {
    private final ConfigSnapshotCleanupService cleanup;
    private final int keepCount;
    private final int batchSize;

    public ConfigSnapshotCleanupJob(ConfigSnapshotCleanupService cleanup, int keepCount, int batchSize) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        if (keepCount <= 0) throw new IllegalArgumentException("keepCount must be positive");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.keepCount = keepCount;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.config.snapshot-cleanup-interval-ms:300000}")
    public void run() {
        cleanup.cleanup(keepCount, batchSize);
    }
}
