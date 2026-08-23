package io.agentteams.controlplane.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment controls for the bounded MCP tools/list discovery cache. */
@Component
@ConfigurationProperties(prefix = "agentteams.mcp.discovery-cache")
public class McpToolDiscoveryCacheProperties {
    private Duration ttl = Duration.ofMinutes(5);
    private int capacity = 256;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }
}
