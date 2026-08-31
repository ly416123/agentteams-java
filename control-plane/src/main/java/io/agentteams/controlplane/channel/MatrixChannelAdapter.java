package io.agentteams.controlplane.channel;

import io.agentteams.controlplane.matrix.MatrixChannelBinding;
import io.agentteams.controlplane.matrix.MatrixChannelBindingRepository;
import io.agentteams.controlplane.matrix.MatrixOutboundRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Matrix outbound adapter. It validates the tenant-scoped room binding and only appends to the
 * existing durable Matrix Outbox; MatrixDeliveryService remains the network delivery owner.
 */
public final class MatrixChannelAdapter implements ChannelPort {
    private final MatrixChannelBindingRepository bindings;
    private final MatrixOutboundRepository outbound;
    private final Clock clock;

    public MatrixChannelAdapter(MatrixChannelBindingRepository bindings, MatrixOutboundRepository outbound,
            Clock clock) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ChannelType type() {
        return ChannelType.MATRIX;
    }

    @Override
    public ChannelReceipt send(ChannelMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.channelType() != type()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "message channel type does not match Matrix adapter");
        }
        UUID bindingId = parseBindingId(message.bindingId());
        MatrixChannelBinding binding = bindings.findById(bindingId)
                .orElseThrow(() -> new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                        "channel binding is not available"));
        if (!binding.matchesScope(message.organizationId(), message.tenantId(), message.projectId())) {
            throw new ChannelDeliveryException(ChannelErrorCategory.AUTH_REJECTED,
                    "channel binding is outside the message scope");
        }
        if (!binding.enabled()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "channel binding is disabled");
        }
        if (!binding.eventTypes().contains(message.eventType())) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "channel event type is not enabled");
        }
        Instant now = clock.instant();
        boolean queued = outbound.enqueue(message.messageId(), binding.roomId(), message.eventType(),
                message.renderedBody(), now);
        return new ChannelReceipt(message.messageId(), message.bindingId(),
                queued ? ChannelReceiptStatus.QUEUED : ChannelReceiptStatus.DUPLICATE, null);
    }

    @Override
    public ChannelHealth health(ChannelBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (binding.type() != type()) {
            throw new ChannelDeliveryException(ChannelErrorCategory.PERMANENT_REJECTION,
                    "binding type does not match Matrix adapter");
        }
        Optional<MatrixChannelBinding> configured = parseOptionalUuid(binding.bindingId()).flatMap(bindings::findById);
        if (configured.isEmpty()) {
            return new ChannelHealth(type(), binding.bindingId(), ChannelHealthStatus.UNAVAILABLE,
                    ChannelErrorCategory.PERMANENT_REJECTION);
        }
        MatrixChannelBinding value = configured.get();
        if (!value.matchesScope(binding.organizationId(), binding.tenantId(), binding.projectId())) {
            return new ChannelHealth(type(), binding.bindingId(), ChannelHealthStatus.UNAVAILABLE,
                    ChannelErrorCategory.AUTH_REJECTED);
        }
        return new ChannelHealth(type(), binding.bindingId(),
                value.enabled() ? ChannelHealthStatus.READY : ChannelHealthStatus.DISABLED, null);
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
