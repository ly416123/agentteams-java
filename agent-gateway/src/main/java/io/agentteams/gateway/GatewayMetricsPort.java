package io.agentteams.gateway;

/** Metrics boundary for the Gateway core. */
public interface GatewayMetricsPort {
    void connectionOpened();
    void connectionClosed();
    void connectionRegistered();
    void eventRejected();

    static GatewayMetricsPort noop() {
        return new GatewayMetricsPort() {
            public void connectionOpened() { }
            public void connectionClosed() { }
            public void connectionRegistered() { }
            public void eventRejected() { }
        };
    }
}
