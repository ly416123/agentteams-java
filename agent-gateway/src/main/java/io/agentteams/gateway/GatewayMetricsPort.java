package io.agentteams.gateway;

/** Metrics boundary for the Gateway core. */
public interface GatewayMetricsPort {
    void connectionOpened();
    void connectionClosed();
    void connectionRegistered();
    default void connectionReplaced() { }
    void eventRejected();
    void commandAppended();
    void commandDeduplicated();
    default void commandReplayed() { }

    default void natsEventProcessed() { }
    default void natsEventRejected() { }
    default void natsConsumerError() { }

    /**
     * Counts a best-effort task event report dropped before reaching NATS.
     * Low-cardinality reason: "invalid" (failed validation) or
     * "publish_failed" (transport error).
     */
    default void taskEventDropped(String reason) { }

    static GatewayMetricsPort noop() {
        return new GatewayMetricsPort() {
            public void connectionOpened() { }
            public void connectionClosed() { }
            public void connectionRegistered() { }
            public void eventRejected() { }
            public void commandAppended() { }
            public void commandDeduplicated() { }
        };
    }
}
