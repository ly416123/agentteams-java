package io.agentteams.controlplane.config;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically removes only database-tracked, expired pending config uploads. */
public final class ConfigUploadCleanupJob {
    private final ConfigUploadService uploads;
    private final int batchSize;

    public ConfigUploadCleanupJob(ConfigUploadService uploads, int batchSize) {
        this.uploads = Objects.requireNonNull(uploads, "uploads");
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.config.upload-cleanup-interval-ms:300000}")
    public void run() {
        uploads.cleanupExpired(batchSize);
    }
}
