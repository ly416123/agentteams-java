package io.agentteams.controlplane.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Delivery boundary for dashboard alerts; transports must not receive credentials from this API. */
public interface DashboardAlertNotificationPort {
    NotificationResult notify(AlertNotification notification);

    record AlertNotification(Instant from, Instant to, List<DashboardAlertService.Alert> alerts) {
        public AlertNotification {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            alerts = List.copyOf(alerts == null ? List.of() : alerts);
        }
    }

    record NotificationResult(String channel, boolean delivered, int alertCount) {
        public NotificationResult {
            Objects.requireNonNull(channel, "channel");
            if (alertCount < 0) throw new IllegalArgumentException("alertCount must not be negative");
        }
    }
}
