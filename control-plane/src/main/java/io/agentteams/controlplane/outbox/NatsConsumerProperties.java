package io.agentteams.controlplane.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentteams.nats")
public class NatsConsumerProperties {

    private boolean enabled;
    private String url = "nats://localhost:4222";
    private int concurrency = 8;
    private int maxAckPending = 32;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxAckPending() {
        return maxAckPending;
    }

    public void setMaxAckPending(int maxAckPending) {
        this.maxAckPending = maxAckPending;
    }

    public void validate() {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        if (maxAckPending < concurrency) {
            throw new IllegalArgumentException("maxAckPending must be at least concurrency");
        }
    }
}
