package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DashboardAlertDeliveryServiceTest {
    private static final Instant FROM = Instant.parse("2026-08-25T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-25T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-25T01:01:00Z");

    @Test
    void suppressesDuplicateWindowAndRuleAfterSuccessfulDelivery() {
        InMemoryDashboardAlertRuleRepository rules = new InMemoryDashboardAlertRuleRepository();
        rules.save(new DashboardAlertRule("FAILURE_RATE", "CRITICAL", 0.10, true));
        InMemoryDashboardAlertEventRepository events = new InMemoryDashboardAlertEventRepository();
        RecordingNotification notification = new RecordingNotification();
        DashboardAlertDeliveryService service = service(rules, events, notification);

        DashboardAlertDeliveryService.DeliveryResult first = service.deliver("tenant-a", "project-a", FROM, TO);
        DashboardAlertDeliveryService.DeliveryResult repeated = service.deliver("tenant-a", "project-a", FROM, TO);

        assertThat(first.delivered()).isEqualTo(1);
        assertThat(repeated.suppressed()).isEqualTo(1);
        assertThat(notification.notifications()).hasSize(1);
        assertThat(events.findDue(NOW, 10)).isEmpty();
    }

    @Test
    void failedDeliveryBecomesRetryableAndIsMarkedSentAfterRetry() {
        InMemoryDashboardAlertRuleRepository rules = new InMemoryDashboardAlertRuleRepository();
        rules.save(new DashboardAlertRule("COST", "WARNING", 1, true));
        InMemoryDashboardAlertEventRepository events = new InMemoryDashboardAlertEventRepository();
        RecordingNotification notification = new RecordingNotification();
        notification.failNext.set(true);
        DashboardAlertDeliveryService service = service(rules, events, notification);

        DashboardAlertDeliveryService.DeliveryResult failed = service.deliver("tenant-a", "project-a", FROM, TO);
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(events.findDue(NOW.plus(Duration.ofMinutes(2)), 10)).hasSize(1);

        DashboardAlertDeliveryService.DeliveryResult retried = service.retryDue(NOW.plus(Duration.ofMinutes(2)));
        assertThat(retried.delivered()).isEqualTo(1);
        assertThat(events.findDue(NOW.plus(Duration.ofMinutes(2)), 10)).isEmpty();
        assertThat(events.findRecent("tenant-a", "project-a", 10))
                .singleElement().extracting(DashboardAlertEvent::attempts).isEqualTo(2);
        assertThat(notification.notifications()).hasSize(2);
    }

    private static DashboardAlertDeliveryService service(
            DashboardAlertRuleRepository rules, DashboardAlertEventRepository events,
            RecordingNotification notification) {
        UsageQueryService.UsageSummary summary = new UsageQueryService.UsageSummary(FROM, TO,
                new UsageQueryService.UsageTotals(10, 2, 100, 200, 5, 50), List.of());
        return new DashboardAlertDeliveryService(
                (tenant, project, from, to) -> summary,
                new DashboardAlertService(rules), events, notification,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(1));
    }

    private static final class RecordingNotification implements DashboardAlertNotificationPort {
        private final List<AlertNotification> notifications = new ArrayList<>();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public NotificationResult notify(AlertNotification notification) {
            notifications.add(notification);
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("webhook unavailable");
            }
            return new NotificationResult("test", true, notification.alerts().size());
        }

        List<AlertNotification> notifications() {
            return notifications;
        }
    }
}
