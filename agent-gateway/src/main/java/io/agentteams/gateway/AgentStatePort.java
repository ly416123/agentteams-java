package io.agentteams.gateway;

import java.time.Instant;

/** Durable/application seam for Agent connection status; it must not persist task state in the registry. */
public interface AgentStatePort {

    void registered(ConnectionRegistry.ConnectionSnapshot connection, Instant at);

    boolean seen(ConnectionRegistry.ConnectionSnapshot connection, Instant at);

    boolean disconnected(ConnectionRegistry.ConnectionSnapshot connection, Instant at);
}
