package io.agentteams.controlplane.channel;

import java.util.Objects;

public record ChannelHealth(ChannelType type, String bindingId, ChannelHealthStatus status,
        ChannelErrorCategory errorCategory) {
    public ChannelHealth {
        Objects.requireNonNull(type, "type");
        if (bindingId == null || bindingId.isBlank()) throw new IllegalArgumentException("bindingId must not be blank");
        Objects.requireNonNull(status, "status");
    }
}
