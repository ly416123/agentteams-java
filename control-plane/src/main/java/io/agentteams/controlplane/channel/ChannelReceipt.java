package io.agentteams.controlplane.channel;

import java.util.Objects;

public record ChannelReceipt(java.util.UUID messageId, String bindingId, ChannelReceiptStatus status,
        ChannelErrorCategory errorCategory) {
    public ChannelReceipt {
        Objects.requireNonNull(messageId, "messageId");
        if (bindingId == null || bindingId.isBlank()) throw new IllegalArgumentException("bindingId must not be blank");
        Objects.requireNonNull(status, "status");
        if (status == ChannelReceiptStatus.QUEUED && errorCategory != null) {
            throw new IllegalArgumentException("queued receipt must not contain an error");
        }
    }
}
