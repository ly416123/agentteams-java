package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Periodically evaluates every known project and retries durable alert deliveries. */
public final class DashboardAlertScheduler {
    private final DashboardAlertDeliveryService delivery;
    private final DashboardAlertEventRepository events;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final Duration window;
    private final int maxProjectsPerRun;

    public DashboardAlertScheduler(DashboardAlertDeliveryService delivery, DashboardAlertEventRepository events,
            SchedulerLeaseService lease, Clock clock, String owner, Duration leaseDuration,
            Duration window, int maxProjectsPerRun) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.events = Objects.requireNonNull(events, "events");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "dashboard-alert" : owner;
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.window = requirePositive(window, "window");
        if (maxProjectsPerRun < 1 || maxProjectsPerRun > 1000) {
            throw new IllegalArgumentException("maxProjectsPerRun must be between 1 and 1000");
        }
        this.maxProjectsPerRun = maxProjectsPerRun;
    }

    @Scheduled(fixedDelayString = "${agentteams.dashboard.alerts.scheduler.poll-interval-ms:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        Instant evaluationAt = now.truncatedTo(ChronoUnit.MINUTES);
        return lease.run("dashboard-alert", owner, now, leaseDuration, () -> {
            DashboardAlertDeliveryService.DeliveryResult retries = delivery.retryDue(now);
            int evaluated = 0;
            int delivered = retries.delivered();
            int failed = retries.failed();
            for (DashboardAlertEventRepository.AlertScope scope : events.findUsageScopes()) {
                if (evaluated >= maxProjectsPerRun) break;
                DashboardAlertDeliveryService.DeliveryResult result = delivery.deliver(scope.tenantId(),
                        scope.projectId(), evaluationAt.minus(window), evaluationAt);
                evaluated++;
                delivered += result.delivered();
                failed += result.failed();
            }
            return new RunResult(true, evaluated, delivered, failed);
        }).valueOr(new RunResult(false, 0, 0, 0));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    public record RunResult(boolean leader, int evaluatedProjects, int delivered, int failed) { }
}
