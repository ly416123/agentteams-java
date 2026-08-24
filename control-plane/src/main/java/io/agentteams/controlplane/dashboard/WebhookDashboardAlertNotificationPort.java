package io.agentteams.controlplane.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Simple deployment-owned webhook adapter, invoked only by the explicit notify endpoint. */
public final class WebhookDashboardAlertNotificationPort implements DashboardAlertNotificationPort {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final URI webhookUrl;
    private final Duration timeout;

    public WebhookDashboardAlertNotificationPort(HttpClient client, ObjectMapper objectMapper,
            URI webhookUrl, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("notification timeout must be between 1ms and 60s");
        }
        if (webhookUrl.getScheme() == null || webhookUrl.getHost() == null || webhookUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("notification webhook URL must be an absolute URL without user info");
        }
    }

    @Override
    public NotificationResult notify(AlertNotification notification) {
        try {
            String body = objectMapper.writeValueAsString(notification);
            HttpRequest request = HttpRequest.newBuilder(webhookUrl).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("dashboard alert webhook HTTP " + response.statusCode());
            }
            return new NotificationResult("webhook", true, notification.alerts().size());
        } catch (IOException error) {
            throw new IllegalStateException("dashboard alert webhook delivery failed", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("dashboard alert webhook delivery interrupted", error);
        }
    }
}
