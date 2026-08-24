package io.agentteams.controlplane.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Safe default when no deployment-owned notification channel is configured. */
public final class LoggingDashboardAlertNotificationPort implements DashboardAlertNotificationPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingDashboardAlertNotificationPort.class);

    @Override
    public NotificationResult notify(AlertNotification notification) {
        notification.alerts().forEach(alert -> LOGGER.warn("dashboard alert rule={} severity={} actual={} message={}",
                alert.rule(), alert.severity(), alert.actual(), alert.message()));
        return new NotificationResult("log", false, notification.alerts().size());
    }
}
