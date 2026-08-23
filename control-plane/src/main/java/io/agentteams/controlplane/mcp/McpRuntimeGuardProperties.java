package io.agentteams.controlplane.mcp;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment controls for the per-server MCP admission and circuit guard. */
@Component
@ConfigurationProperties(prefix = "agentteams.mcp.runtime-guard")
public class McpRuntimeGuardProperties {
    private int maxConcurrentRequests = 64;
    private int failureThreshold = 5;
    private Duration cooldown = Duration.ofSeconds(30);

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    /** Alias that keeps the configuration terminology useful to callers/tests. */
    public int getMaxInFlight() {
        return maxConcurrentRequests;
    }

    public void setMaxInFlight(int maxInFlight) {
        this.maxConcurrentRequests = maxInFlight;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getCooldown() {
        return cooldown;
    }

    public void setCooldown(Duration cooldown) {
        this.cooldown = cooldown;
    }

    /** Validates values at construction time so a malformed deployment fails closed. */
    public void validate() {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException("MCP runtime guard max-concurrent-requests must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("MCP runtime guard failure-threshold must be positive");
        }
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("MCP runtime guard cooldown must be positive");
        }
    }
}
