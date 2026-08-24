package io.agentteams.controlplane.dashboard;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned webhook settings; unset means log-only delivery. */
@ConfigurationProperties(prefix = "agentteams.dashboard.alerts.notification")
public class DashboardAlertNotificationProperties {
    private boolean enabled;
    private URI webhookUrl;
    private Duration timeout = Duration.ofSeconds(3);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(URI webhookUrl) { this.webhookUrl = webhookUrl; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
}
