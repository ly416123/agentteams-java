package io.agentteams.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentteams.gateway.nats")
public class NatsGatewayProperties {

    private boolean enabled;
    private String url = "nats://localhost:4222";
    private String subject = "task.events.*";
    private String durable = "agent-gateway";
    private String configSubject = "agent.events.*";
    private String configDurable = "agent-gateway-config";
    private String instanceId = "local";

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

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getDurable() {
        return durable;
    }

    public void setDurable(String durable) {
        this.durable = durable;
    }

    public String getConfigSubject() {
        return configSubject;
    }

    public void setConfigSubject(String configSubject) {
        this.configSubject = configSubject;
    }

    public String getConfigDurable() {
        return configDurable;
    }

    public void setConfigDurable(String configDurable) {
        this.configDurable = configDurable;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
    }

    /** Each Gateway replica consumes the event stream independently for fan-out. */
    public String taskConsumerDurable() {
        return scoped(durable);
    }

    public String configConsumerDurable() {
        return scoped(configDurable);
    }

    private String scoped(String base) {
        return base + "-" + instanceId.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
