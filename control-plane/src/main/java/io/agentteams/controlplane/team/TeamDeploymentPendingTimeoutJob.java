package io.agentteams.controlplane.team;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only periodic timeout of team deployment members that were never acknowledged. */
public final class TeamDeploymentPendingTimeoutJob {
    private final TeamDeploymentPendingTimeoutService service;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final Duration pendingTimeout;
    private final int batchSize;

    public TeamDeploymentPendingTimeoutJob(TeamDeploymentPendingTimeoutService service, SchedulerLeaseService lease,
            Clock clock, String owner, Duration leaseDuration, Duration pendingTimeout, int batchSize) {
        this.service = Objects.requireNonNull(service, "service");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "team-deployment-pending-timeout" : owner.trim();
        this.leaseDuration = leaseDuration;
        this.pendingTimeout = pendingTimeout;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.team-deployment-pending-timeout.reconcile-interval-ms:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<TeamDeploymentPendingTimeoutService.TimeoutResult> result = lease.run(
                "team-deployment-pending-timeout", owner, now, leaseDuration,
                () -> service.reconcile(now, pendingTimeout, batchSize));
        if (!result.leader()) return new RunResult(false, 0);
        return new RunResult(true, result.value().failed());
    }

    public record RunResult(boolean leader, int failed) {
        public RunResult {
            if (failed < 0) {
                throw new IllegalArgumentException("failed must not be negative");
            }
        }
    }
}
