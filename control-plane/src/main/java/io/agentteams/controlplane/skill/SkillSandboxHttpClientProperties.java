package io.agentteams.controlplane.skill;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned, explicit opt-in settings for the enterprise sandbox HTTP adapter. */
@ConfigurationProperties(prefix = "agentteams.skill.security-scanner.http")
public class SkillSandboxHttpClientProperties {
    private boolean enabled;
    private URI endpoint;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int maxResponseBytes = 64 * 1024;

    public void validate() {
        if (!enabled) return;
        if (endpoint == null) throw new IllegalArgumentException("sandbox HTTP endpoint is required");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || (!"http".equalsIgnoreCase(endpoint.getScheme())
                        && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("sandbox HTTP endpoint must be an absolute http(s) URI without credentials or fragment");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
        if (connectTimeout.compareTo(requestTimeout) > 0) {
            throw new IllegalArgumentException("sandbox HTTP connect-timeout must not exceed request-timeout");
        }
        if (requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("sandbox HTTP request-timeout must not exceed 30s");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024) {
            throw new IllegalArgumentException("sandbox HTTP max-response-bytes must be between 1 and 1048576");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("sandbox HTTP " + field + " must be positive");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
