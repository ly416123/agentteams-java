package io.agentteams.controlplane.webhook;

public interface WebhookTransport {
    void send(WebhookDelivery delivery);
}
