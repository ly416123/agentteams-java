package io.agentteams.controlplane.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.outbox.EventEnvelope;
import java.net.URI;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Durable Webhook orchestration; transport failures never change Task state. */
@Service
public final class WebhookDeliveryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "task.created", "task.updated", "task.completed", "task.failed", "task.process", "task.result");
    private final WebhookRepository repository;
    private final WebhookTransport transport;
    private final int maxAttempts;

    public WebhookDeliveryService(WebhookRepository repository, WebhookTransport transport) {
        this(repository, transport, 5);
    }

    @Autowired
    public WebhookDeliveryService(WebhookRepository repository, WebhookTransport transport,
            @Value("${agentteams.webhook.scheduler.max-attempts:5}") int maxAttempts) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("maxAttempts must be 1..20");
        this.maxAttempts = maxAttempts;
    }

    public WebhookSubscription create(CreateRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        if (request.eventTypes() == null || request.eventTypes().isEmpty()
                || !ALLOWED_EVENTS.containsAll(request.eventTypes())) {
            throw new IllegalArgumentException("eventTypes contains unsupported event");
        }
        URI endpoint = WebhookEndpointPolicy.requireSafe(request.endpoint());
        String secretRef = required(request.secretRef(), "secretRef");
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(), request.scope(), endpoint.toString(),
                secretRef, request.eventTypes(), true, 0, now, now);
        return repository.insert(subscription);
    }

    public WebhookSubscription create(CreateRequest request, java.time.Clock clock) {
        return create(request, Objects.requireNonNull(clock, "clock").instant());
    }

    public List<WebhookSubscription> list(WebhookScope scope) {
        return repository.list(Objects.requireNonNull(scope, "scope"));
    }

    public boolean enqueue(WebhookScope scope, EventEnvelope event, Instant now) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(event, "event");
        rejectSensitive(event.payload());
        if (!ALLOWED_EVENTS.contains(event.eventType())) return false;
        boolean enqueued = false;
        for (WebhookSubscription subscription : repository.listEnabled(scope)) {
            if (subscription.eventTypes().contains(event.eventType())) {
                enqueued |= repository.enqueue(subscription, event, now);
            }
        }
        return enqueued;
    }

    public DeliveryResult deliverDue(Instant now, int limit) {
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (WebhookDelivery delivery : repository.findDue(now, limit)) {
            try {
                transport.send(delivery);
                repository.markSent(delivery, now);
                sent++;
            } catch (RuntimeException failure) {
                String error = safeError(failure);
                if (delivery.attempts() + 1 >= maxAttempts) {
                    repository.markDead(delivery, error, now);
                    dead++;
                } else {
                    repository.markRetry(delivery, now.plusSeconds(1L << Math.min(delivery.attempts(), 10)), error, now);
                    retried++;
                }
            }
        }
        return new DeliveryResult(sent, retried, dead);
    }

    private static void rejectSensitive(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT);
                if (key.contains("secret") || key.contains("password") || key.contains("credential")
                        || key.contains("authorization") || key.contains("api_key")) {
                    throw new IllegalArgumentException("Webhook payload contains sensitive field");
                }
                rejectSensitive(field.getValue());
            }
        } else if (node.isArray()) node.forEach(WebhookDeliveryService::rejectSensitive);
    }

    private static String safeError(RuntimeException error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record CreateRequest(WebhookScope scope, String endpoint, String secretRef, Set<String> eventTypes) { }
    public record DeliveryResult(int sent, int retried, int dead) {
        public DeliveryResult {
            if (sent < 0 || retried < 0 || dead < 0) throw new IllegalArgumentException("delivery counts must not be negative");
        }
    }
}
