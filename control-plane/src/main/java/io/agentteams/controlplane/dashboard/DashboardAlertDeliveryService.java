package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Evaluates and delivers dashboard alerts with durable idempotency and retry state. */
public final class DashboardAlertDeliveryService {
    private static final int RETRY_ERROR_LIMIT = 500;
    private static final int DEFAULT_RETRY_LIMIT = 100;

    private final UsageSummaryProvider usage;
    private final DashboardAlertService alerts;
    private final DashboardAlertEventRepository events;
    private final DashboardAlertNotificationPort notifications;
    private final Clock clock;
    private final Duration retryDelay;

    public DashboardAlertDeliveryService(UsageSummaryProvider usage, DashboardAlertService alerts,
            DashboardAlertEventRepository events, DashboardAlertNotificationPort notifications,
            Clock clock, Duration retryDelay) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.events = Objects.requireNonNull(events, "events");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isZero() || retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must be positive");
    }

    public DeliveryResult deliver(String tenantId, String projectId, Instant from, Instant to) {
        requireScope(tenantId, projectId);
        Instant now = clock.instant();
        UsageQueryService.UsageSummary result = usage.summarize(tenantId, projectId, from, to);
        Instant windowFrom = result.from();
        Instant windowTo = result.to();
        List<DashboardAlertService.Alert> evaluated = alerts.evaluate(toDashboardSummary(result));
        List<DashboardAlertEvent> claimed = evaluated.stream()
                .map(alert -> DashboardAlertEvent.pending(fingerprint(tenantId, projectId, alert.rule(), windowFrom, windowTo),
                        tenantId, projectId, alert, windowFrom, windowTo, now))
                .map(candidate -> events.claim(candidate, now))
                .flatMap(java.util.Optional::stream)
                .toList();
        int suppressed = evaluated.size() - claimed.size();
        return deliverClaimed(claimed, suppressed, now);
    }

    public DeliveryResult retryDue(Instant now) {
        Objects.requireNonNull(now, "now");
        List<DashboardAlertEvent> due = events.findDue(now, DEFAULT_RETRY_LIMIT);
        List<DashboardAlertEvent> claimed = due.stream()
                .map(event -> events.claim(event.retryClaimed(now), now))
                .flatMap(java.util.Optional::stream)
                .toList();
        return deliverClaimed(claimed, 0, now);
    }

    private DeliveryResult deliverClaimed(List<DashboardAlertEvent> claimed, int suppressed, Instant now) {
        if (claimed.isEmpty()) return new DeliveryResult(0, suppressed, 0);
        DashboardAlertNotificationPort.AlertNotification notification = new DashboardAlertNotificationPort.AlertNotification(
                claimed.get(0).from(), claimed.get(0).to(), claimed.stream().map(DashboardAlertEvent::alert).toList());
        try {
            DashboardAlertNotificationPort.NotificationResult result = notifications.notify(notification);
            if (!result.delivered()) {
                throw new IllegalStateException("notification channel did not deliver the alert");
            }
            claimed.forEach(event -> events.markSent(event.id(), now));
            return new DeliveryResult(claimed.size(), suppressed, 0);
        } catch (RuntimeException error) {
            Instant nextAttempt = now.plus(backoff(eventAttempts(claimed), retryDelay));
            String message = sanitize(error);
            claimed.forEach(event -> events.markFailed(event.id(), nextAttempt, message, now));
            return new DeliveryResult(0, suppressed, claimed.size());
        }
    }

    private static DashboardSummaryController.DashboardSummary toDashboardSummary(UsageQueryService.UsageSummary result) {
        UsageQueryService.UsageTotals totals = result.totals();
        return new DashboardSummaryController.DashboardSummary(result.from(), result.to(), totals.calls(),
                totals.failures(), totals.promptTokens(), totals.completionTokens(), totals.costUsd(),
                totals.averageLatencyMillis(), result.groups().stream()
                        .map(DashboardSummaryController.DashboardGroup::from).toList());
    }

    private static String fingerprint(String tenant, String project, String rule, Instant from, Instant to) {
        String value = String.join("\n", tenant, project, rule, from.toString(), to.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sanitize(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.length() <= RETRY_ERROR_LIMIT ? message : message.substring(0, RETRY_ERROR_LIMIT);
    }

    private static int eventAttempts(List<DashboardAlertEvent> events) {
        return events.stream().mapToInt(DashboardAlertEvent::attempts).max().orElse(1);
    }

    private static Duration backoff(int attempts, Duration base) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        return base.multipliedBy(1L << exponent);
    }

    private static void requireScope(String tenantId, String projectId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
    }

    @FunctionalInterface
    public interface UsageSummaryProvider {
        UsageQueryService.UsageSummary summarize(String tenantId, String projectId, Instant from, Instant to);
    }

    public record DeliveryResult(int delivered, int suppressed, int failed) {
        public DeliveryResult {
            if (delivered < 0 || suppressed < 0 || failed < 0) {
                throw new IllegalArgumentException("delivery result counts must not be negative");
            }
        }
    }
}
