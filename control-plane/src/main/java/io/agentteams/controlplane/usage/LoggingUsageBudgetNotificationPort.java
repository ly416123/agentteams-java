package io.agentteams.controlplane.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Safe default channel for deployments that have not configured an external notifier. */
public final class LoggingUsageBudgetNotificationPort implements UsageBudgetNotificationPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingUsageBudgetNotificationPort.class);

    @Override
    public NotificationResult notify(UsageBudgetNotification notification) {
        LOGGER.warn("usage budget alert policy={} tenant={} project={} status={} actual={} forecast={} currency={}",
                notification.policyId(), notification.tenantId(), notification.projectId(), notification.status(),
                notification.actualCost(), notification.forecastCost(), notification.currency());
        return new NotificationResult("log", true);
    }
}
