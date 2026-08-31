package io.agentteams.controlplane.channel;

public final class ChannelDeliveryException extends RuntimeException {
    private final ChannelErrorCategory category;

    public ChannelDeliveryException(ChannelErrorCategory category, String message) {
        super(message);
        this.category = category;
    }

    public ChannelErrorCategory category() {
        return category;
    }
}
