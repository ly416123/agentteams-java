package io.agentteams.controlplane.webhook;

public interface WebhookSecretResolver {
    String resolve(String secretRef);
}
