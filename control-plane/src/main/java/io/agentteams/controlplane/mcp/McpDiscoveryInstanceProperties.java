package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Per-process identity and freshness policy for durable MCP discovery observations. */
@Component
@ConfigurationProperties(prefix = "agentteams.mcp.discovery")
public class McpDiscoveryInstanceProperties {
    private static final Pattern INSTANCE_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    private String instanceId = defaultInstanceId();
    private Duration observationTtl = Duration.ofMinutes(2);

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Duration getObservationTtl() {
        return observationTtl;
    }

    public void setObservationTtl(Duration observationTtl) {
        this.observationTtl = observationTtl;
    }

    public void validate() {
        String value = instanceId == null ? "" : instanceId.trim();
        if (value.isEmpty() || value.length() > 128 || !INSTANCE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("MCP discovery instanceId must be a bounded safe identifier");
        }
        if (observationTtl == null || observationTtl.isZero() || observationTtl.isNegative()) {
            throw new IllegalArgumentException("MCP discovery observationTtl must be positive");
        }
        instanceId = value;
    }

    private static String defaultInstanceId() {
        String hostname = System.getenv("HOSTNAME");
        return hostname == null || hostname.isBlank() ? "control-plane" : hostname.toLowerCase(Locale.ROOT);
    }
}
