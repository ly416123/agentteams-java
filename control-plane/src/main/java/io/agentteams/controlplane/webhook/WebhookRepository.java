package io.agentteams.controlplane.webhook;

import io.agentteams.controlplane.outbox.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookRepository {
    WebhookSubscription insert(WebhookSubscription subscription);

    Optional<WebhookSubscription> findById(UUID id);

    List<WebhookSubscription> list(WebhookScope scope);

    List<WebhookSubscription> listEnabled(WebhookScope scope);

    boolean enqueue(WebhookSubscription subscription, EventEnvelope event, Instant now);

    List<WebhookDelivery> findDue(Instant now, int limit);

    void markSent(WebhookDelivery delivery, Instant now);

    void markRetry(WebhookDelivery delivery, Instant nextAttemptAt, String error, Instant now);

    void markDead(WebhookDelivery delivery, String error, Instant now);
}
