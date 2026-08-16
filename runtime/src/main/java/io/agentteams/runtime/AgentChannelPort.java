package io.agentteams.runtime;

import io.agentteams.contracts.v1.AgentMessage;

@FunctionalInterface
public interface AgentChannelPort {
    void send(AgentMessage message);
}
