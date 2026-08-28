package io.agentteams.controlplane.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs price imports on one control-plane replica at a time. */
public final class ModelPriceSyncScheduler {
    private final ModelPriceSyncService sync;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;

    public ModelPriceSyncScheduler(ModelPriceSyncService sync, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration) {
        this.sync = Objects.requireNonNull(sync, "sync");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "model-price-sync" : owner;
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    @Scheduled(fixedDelayString = "${agentteams.usage.price-sync.poll-interval-ms:3600000}")
    public void scheduledRun() { runOnce(); }

    public RunResult runOnce() {
        Instant now = clock.instant();
        ModelPriceSyncService.RunResult result = lease.run("model-price-sync", owner, now, leaseDuration,
                () -> sync.runOnce(now)).valueOr(null);
        if (result == null) return new RunResult(false, 0, 0, 0, 0);
        return new RunResult(true, result.targetCount(), result.fetchedQuotes(), result.inserted(), result.skipped());
    }

    public record RunResult(boolean leader, int targetCount, int fetchedQuotes, int inserted, int skipped) { }
}
