package io.agentteams.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional Gateway-to-Control-Plane artifact upload bridge. */
@ConfigurationProperties(prefix = "agentteams.gateway.artifact")
public class GatewayArtifactUploadProperties {
    private boolean enabled;
    private URI controlPlaneUrl;
    private String internalToken = "";
    private Duration requestTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getControlPlaneUrl() {
        return controlPlaneUrl;
    }

    public void setControlPlaneUrl(URI controlPlaneUrl) {
        this.controlPlaneUrl = controlPlaneUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
