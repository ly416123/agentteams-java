package io.agentteams.controlplane.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-owned policy for the opt-in HTTP provider probe.
 *
 * <p>The host allowlist is deliberately explicit. An empty allowlist does not
 * mean "all hosts"; it makes every endpoint fail validation.</p>
 */
@ConfigurationProperties(prefix = "agentteams.model-provider.connection-probe")
public class ModelProviderConnectionProbeProperties {

    private boolean enabled;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration maxTimeout = Duration.ofSeconds(10);
    private List<String> allowedSchemes = new ArrayList<>(List.of("https"));
    private List<String> allowedHosts = new ArrayList<>();

    public void validate() {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("provider probe connect-timeout must be between 1ms and 60s");
        }
        if (maxTimeout == null || maxTimeout.isZero() || maxTimeout.isNegative()
                || maxTimeout.compareTo(Duration.ofMillis(ValidationOnlyModelProviderConnectionProbe.MAX_TIMEOUT_MILLIS)) > 0) {
            throw new IllegalArgumentException("provider probe max-timeout must be between 1ms and 60s");
        }
        if (connectTimeout.compareTo(maxTimeout) > 0) {
            throw new IllegalArgumentException("provider probe connect-timeout must not exceed max-timeout");
        }
        validateValues(allowedSchemes, "allowed-schemes");
        validateValues(allowedHosts, "allowed-hosts");
    }

    private static void validateValues(List<String> values, String name) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("provider probe " + name + " must not be empty");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    public void setMaxTimeout(Duration maxTimeout) {
        this.maxTimeout = maxTimeout;
    }

    public List<String> getAllowedSchemes() {
        return List.copyOf(allowedSchemes);
    }

    public void setAllowedSchemes(List<String> allowedSchemes) {
        this.allowedSchemes = allowedSchemes == null ? new ArrayList<>() : new ArrayList<>(allowedSchemes);
    }

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }
}
