package io.agentteams.controlplane.channel;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.outbox.EventEnvelope;
import io.agentteams.controlplane.webhook.WebhookRepository;
import io.agentteams.controlplane.webhook.WebhookSubscription;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Webhook Channel adapter. It only creates a durable delivery row; the existing leader-only
 * webhook scheduler owns HTTP delivery, retry, dead-lettering and HMAC signing.
 */
public final class WebhookChannelAdapter implements ChannelPort {
    private final WebhookRepository repository;
    private final Clock clock;

    public WebhookChannelAdapter(WebhookRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public ChannelReceipt send(ChannelMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.channelType() != type()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "message channel type does not match webhook adapter");
        }
        UUID bindingId = parseBindingId(message.bindingId());
        WebhookSubscription subscription = repository.findById(bindingId)
                .orElseThrow(() -> new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                        "channel binding is not available"));
        ensureScope(subscription, message);
        if (!subscription.enabled()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "channel binding is disabled");
        }
        if (!subscription.eventTypes().contains(message.eventType())) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "channel event type is not enabled");
        }
        Instant now = clock.instant();
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("message_id", message.messageId().toString());
        body.put("channel_type", message.channelType().name());
        body.put("body", message.renderedBody());
        EventEnvelope event = new EventEnvelope(message.messageId(), message.eventType(), "channel", bindingId, 0, now,
                body, message.correlationId(), "", "");
        boolean queued = repository.enqueue(subscription, event, now);
        return new ChannelReceipt(message.messageId(), message.bindingId(),
                queued ? ChannelReceiptStatus.QUEUED : ChannelReceiptStatus.DUPLICATE, null);
    }

    @Override
    public ChannelHealth health(ChannelBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (binding.type() != type()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "binding type does not match webhook adapter");
        }
        Optional<WebhookSubscription> subscription = parseOptionalUuid(binding.bindingId()).flatMap(repository::findById);
        if (subscription.isEmpty()) {
            return new ChannelHealth(type(), binding.bindingId(), ChannelHealthStatus.UNAVAILABLE,
                    ChannelErrorCategory.PERMANENT_REJECTION);
        }
        WebhookSubscription value = subscription.get();
        if (!matchesScope(value, binding.organizationId(), binding.tenantId(), binding.projectId())) {
            return new ChannelHealth(type(), binding.bindingId(), ChannelHealthStatus.UNAVAILABLE,
                    ChannelErrorCategory.AUTH_REJECTED);
        }
        return new ChannelHealth(type(), binding.bindingId(),
                value.enabled() ? ChannelHealthStatus.READY : ChannelHealthStatus.DISABLED, null);
    }

    private static void ensureScope(WebhookSubscription subscription, ChannelMessage message) {
        if (!matchesScope(subscription, message.organizationId(), message.tenantId(), message.projectId())) {
            throw new ChannelDeliveryException(ChannelErrorCategory.AUTH_REJECTED,
                    "channel binding is outside the message scope");
        }
    }

    private static boolean matchesScope(WebhookSubscription subscription, String organizationId, String tenantId,
            String projectId) {
        return subscription.scope().organizationId().equals(organizationId)
                && subscription.scope().tenantId().equals(tenantId)
                && Objects.equals(subscription.scope().projectId(), projectId);
    }

    private static UUID parseBindingId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "channel binding id is invalid");
        }
    }

    private static Optional<UUID> parseOptionalUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }
}
