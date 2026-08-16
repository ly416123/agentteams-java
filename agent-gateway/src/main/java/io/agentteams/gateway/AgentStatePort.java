package io.agentteams.gateway;

import java.time.Instant;

/** Durable/application seam for Agent connection status; it must not persist task state in the registry. */
public interface AgentStatePort {

    void registered(AgentProfile profile, Instant at);

    void seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at);

    void disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at);
}
