package io.agentteams.gateway;

import io.agentteams.contracts.v1.TaskAccepted;
import io.agentteams.contracts.v1.TaskCompleted;
import io.agentteams.contracts.v1.TaskFailed;
import io.agentteams.contracts.v1.TaskHeartbeat;
import io.agentteams.contracts.v1.TaskProgress;

/** Application/domain seam for inbound Agent execution events. */
public interface GatewayApplicationHandler {

    void taskAccepted(ConnectionRegistry.ConnectionSnapshot connection, TaskAccepted event);

    void taskProgress(ConnectionRegistry.ConnectionSnapshot connection, TaskProgress event);

    void taskHeartbeat(ConnectionRegistry.ConnectionSnapshot connection, TaskHeartbeat event);

    void taskCompleted(ConnectionRegistry.ConnectionSnapshot connection, TaskCompleted event);

    void taskFailed(ConnectionRegistry.ConnectionSnapshot connection, TaskFailed event);

    default void configApplied(ConnectionRegistry.ConnectionSnapshot connection,
            io.agentteams.contracts.v1.ConfigApplied event) {
        // Configuration acknowledgements are optional for gateway-only deployments.
    }

    default void agentHeartbeat(ConnectionRegistry.ConnectionSnapshot connection,
            io.agentteams.contracts.v1.AgentHeartbeat event) {
        // Presence is refreshed by InboundEventHandler after routing.
    }
}
