package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-owned safety controls for the real MCP HTTP connector.
 *
 * <p>An empty host and endpoint allowlist is intentional and fails closed. Exact endpoints take
 * precedence when configured; otherwise host patterns may be exact names or {@code *.example.com}.
 * Credentials are not a property of this connector and cannot be configured here.</p>
 */
@ConfigurationProperties(prefix = "agentteams.mcp.http")
public class McpHttpConnectorProperties {
    private boolean enabled;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration maxTimeout = Duration.ofSeconds(30);
    private List<String> allowedSchemes = new ArrayList<>(List.of("https"));
    private List<String> allowedHosts = new ArrayList<>();
    private List<String> allowedEndpoints = new ArrayList<>();
    private int maxResponseBytes = 1_048_576;

    public void validate() {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(maxTimeout, "max-timeout");
        if (connectTimeout.compareTo(maxTimeout) > 0) {
            throw new IllegalArgumentException("MCP HTTP connect-timeout must not exceed max-timeout");
        }
        if (maxTimeout.compareTo(java.time.Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("MCP HTTP max-timeout must not exceed 30s");
        }
        requireValues(allowedSchemes, "allowed-schemes");
        requireValues(allowedHosts, "allowed-hosts");
        requireValues(allowedEndpoints, "allowed-endpoints");
        if (allowedSchemes.stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> !value.equals("http") && !value.equals("https"))) {
            throw new IllegalArgumentException("MCP HTTP allowed-schemes must contain only http or https");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("MCP HTTP max-response-bytes must be between 1 and 16777216");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("MCP HTTP " + field + " must be positive");
        }
    }

    private static void requireValues(List<String> values, String field) {
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("MCP HTTP " + field + " must not contain blank values");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
    public Duration getMaxTimeout() { return maxTimeout; }
    public void setMaxTimeout(Duration value) { this.maxTimeout = value; }
    public List<String> getAllowedSchemes() { return List.copyOf(allowedSchemes); }
    public void setAllowedSchemes(List<String> values) { this.allowedSchemes = values == null ? new ArrayList<>() : new ArrayList<>(values); }
    public List<String> getAllowedHosts() { return List.copyOf(allowedHosts); }
    public void setAllowedHosts(List<String> values) { this.allowedHosts = values == null ? new ArrayList<>() : new ArrayList<>(values); }
    public List<String> getAllowedEndpoints() { return List.copyOf(allowedEndpoints); }
    public void setAllowedEndpoints(List<String> values) { this.allowedEndpoints = values == null ? new ArrayList<>() : new ArrayList<>(values); }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { this.maxResponseBytes = value; }
}
