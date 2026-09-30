package io.agentteams.controlplane.matrix;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentteams.matrix.delivery")
public class MatrixDeliveryProperties {
    private boolean enabled;
    private String homeserverUrl = "";
    private String accessToken = "";
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int batchSize = 20;
    private int maxResponseBytes = 8192;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHomeserverUrl() { return homeserverUrl; }
    public void setHomeserverUrl(String value) { homeserverUrl = value == null ? "" : value.trim(); }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String value) { accessToken = value == null ? "" : value; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration value) { pollInterval = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
}
