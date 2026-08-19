package io.agentteams.gateway;

/** Metrics boundary for the Gateway core. */
public interface GatewayMetricsPort {
    void connectionOpened();
    void connectionClosed();
    void connectionRegistered();
    void eventRejected();
    void commandAppended();
    void commandDeduplicated();

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
