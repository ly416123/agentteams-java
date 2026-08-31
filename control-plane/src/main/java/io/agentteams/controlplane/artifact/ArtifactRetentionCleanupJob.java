package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only retention pump; tombstones make deletion restart-safe. */
public final class ArtifactRetentionCleanupJob {
    private final ArtifactRetentionService retention;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final ArtifactRetentionPolicy fallback;
    private final int batchSize;

    public ArtifactRetentionCleanupJob(ArtifactRetentionService retention, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, ArtifactRetentionPolicy fallback, int batchSize) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "artifact-retention" : owner.trim();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.artifact-retention.cleanup-interval-ms:300000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<ArtifactRetentionService.CleanupResult> result = lease.run(
                "artifact-retention", owner, now, leaseDuration, () -> retention.cleanup(fallback, batchSize));
        return result.leader() ? map(true, result.value()) : new RunResult(false, 0, 0, 0, 0);
    }

    private static RunResult map(boolean leader, ArtifactRetentionService.CleanupResult result) {
        return new RunResult(leader, result.tombstoned(), result.held(), result.deleted(), result.failed());
    }

    public record RunResult(boolean leader, int tombstoned, int held, int deleted, int failed) {
        public RunResult {
            if (tombstoned < 0 || held < 0 || deleted < 0 || failed < 0) {
                throw new IllegalArgumentException("cleanup counts must not be negative");
            }
        }
    }
}
