package io.agentteams.controlplane.webhook;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only delivery pump; each row remains durable across control-plane restarts. */
public final class WebhookDeliveryScheduler {
    private final WebhookDeliveryService delivery;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;

    public WebhookDeliveryScheduler(WebhookDeliveryService delivery, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, int batchSize) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "webhook-delivery" : owner.trim();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.webhook.scheduler.poll-interval-ms:1000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<WebhookDeliveryService.DeliveryResult> result = lease.run(
                "webhook-delivery", owner, now, leaseDuration, () -> delivery.deliverDue(now, batchSize));
        return result.leader() ? map(true, result.value()) : new RunResult(false, 0, 0, 0);
    }

    private static RunResult map(boolean leader, WebhookDeliveryService.DeliveryResult result) {
        return new RunResult(leader, result.sent(), result.retried(), result.dead());
    }

    public record RunResult(boolean leader, int sent, int retried, int dead) {
        public RunResult {
            if (sent < 0 || retried < 0 || dead < 0) throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
