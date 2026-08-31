package io.agentteams.controlplane.channel;

public interface ChannelPort {
    ChannelType type();

    ChannelReceipt send(ChannelMessage message);

    ChannelHealth health(ChannelBinding binding);
}
