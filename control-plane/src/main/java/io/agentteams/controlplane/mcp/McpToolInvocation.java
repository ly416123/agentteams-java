package io.agentteams.controlplane.mcp;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded runtime input for one MCP tool call. */
public record McpToolInvocation(String toolName, Map<String, Object> arguments, Duration timeout) {
    public McpToolInvocation {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName is required");
        toolName = toolName.trim();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        Map<String, Object> copy = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        copy.forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("argument names must not be blank");
        });
        arguments = Collections.unmodifiableMap(copy);
    }
}
