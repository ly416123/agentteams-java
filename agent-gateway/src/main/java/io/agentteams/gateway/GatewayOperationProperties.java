package io.agentteams.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional Gateway-to-Control-Plane worker observation bridge. */
@ConfigurationProperties(prefix = "agentteams.gateway.worker-operations")
public class GatewayOperationProperties {
    private boolean remoteEnabled;
    private URI controlPlaneUrl;
    private String internalToken = "";
    private Duration requestTimeout = Duration.ofSeconds(3);

    public boolean isRemoteEnabled() { return remoteEnabled; }
    public void setRemoteEnabled(boolean remoteEnabled) { this.remoteEnabled = remoteEnabled; }
    public URI getControlPlaneUrl() { return controlPlaneUrl; }
    public void setControlPlaneUrl(URI controlPlaneUrl) { this.controlPlaneUrl = controlPlaneUrl; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
