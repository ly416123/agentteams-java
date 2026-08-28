package io.agentteams.controlplane.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned, opt-in policy for importing a provider price snapshot. */
@ConfigurationProperties(prefix = "agentteams.usage.price-sync")
public class ModelPriceSyncProperties {
    private boolean enabled;
    private URI endpoint;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private long pollIntervalMs = Duration.ofHours(1).toMillis();
    private Duration leaseDuration = Duration.ofSeconds(30);
    private int maxResponseBytes = 262_144;
    private int maxQuotes = 1_000;
    private List<Target> targets = new ArrayList<>();

    public void validate() {
        if (!enabled) return;
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new IllegalArgumentException("price-sync endpoint must be an absolute http(s) URI without credentials or fragment");
        }
        validateDuration(connectTimeout, "connect-timeout", Duration.ofSeconds(60));
        validateDuration(requestTimeout, "request-timeout", Duration.ofMinutes(2));
        if (connectTimeout.compareTo(requestTimeout) > 0) {
            throw new IllegalArgumentException("price-sync connect-timeout must not exceed request-timeout");
        }
        if (pollIntervalMs < 1 || pollIntervalMs > Duration.ofDays(7).toMillis()) {
            throw new IllegalArgumentException("price-sync poll-interval-ms is outside the allowed range");
        }
        validateDuration(leaseDuration, "lease-duration", Duration.ofMinutes(10));
        if (maxResponseBytes < 1 || maxResponseBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("price-sync max-response-bytes must be between 1 and 10485760");
        }
        if (maxQuotes < 1 || maxQuotes > 10_000) {
            throw new IllegalArgumentException("price-sync max-quotes must be between 1 and 10000");
        }
        if (targets == null || targets.isEmpty() || targets.stream().anyMatch(target -> target == null || !target.valid())) {
            throw new IllegalArgumentException("price-sync targets must contain at least one tenant and project");
        }
    }

    private static void validateDuration(Duration value, String field, Duration max) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("price-sync " + field + " is outside the allowed range");
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
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public int getMaxQuotes() { return maxQuotes; }
    public void setMaxQuotes(int maxQuotes) { this.maxQuotes = maxQuotes; }
    public List<Target> getTargets() { return List.copyOf(targets); }
    public void setTargets(List<Target> targets) { this.targets = targets == null ? new ArrayList<>() : new ArrayList<>(targets); }

    public record Target(String tenant, String project) {
        public boolean valid() { return tenant != null && !tenant.isBlank() && project != null && !project.isBlank(); }
    }
}
