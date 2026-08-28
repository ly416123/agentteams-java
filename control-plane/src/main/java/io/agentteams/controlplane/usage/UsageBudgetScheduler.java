package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically evaluates active budget policies while holding the database lease. */
public final class UsageBudgetScheduler {
    private final UsageBudgetDeliveryService delivery;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int maxPoliciesPerRun;

    public UsageBudgetScheduler(UsageBudgetDeliveryService delivery, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, int maxPoliciesPerRun) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "usage-budget" : owner;
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        if (maxPoliciesPerRun < 1 || maxPoliciesPerRun > 1000) {
            throw new IllegalArgumentException("maxPoliciesPerRun must be between 1 and 1000");
        }
        this.maxPoliciesPerRun = maxPoliciesPerRun;
    }

    @Scheduled(fixedDelayString = "${agentteams.usage.budget.scheduler.poll-interval-ms:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        return lease.run("usage-budget", owner, now, leaseDuration,
                () -> delivery.runOnce(now, maxPoliciesPerRun).toSchedulerResult())
                .valueOr(new RunResult(false, 0, 0, 0));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public record RunResult(boolean leader, int evaluatedPolicies, int delivered, int failed) { }
}
