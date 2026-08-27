package io.agentteams.gateway;

import java.time.Instant;

/** Reports the Gateway's live Worker connection facts to an active rollout. */
public interface GatewayWorkerOperationObservationReporter {
    void report(ConnectionRegistry.ConnectionSnapshot connection, boolean online, Instant observedAt);

    static GatewayWorkerOperationObservationReporter noop() {
        return (connection, online, observedAt) -> { };
    }
}
