package io.agentteams.application.api;

import java.util.Locale;

/** Supported execution backends for an AgentTeams worker. */
public enum ExecutionRuntime {
    QWENPAW,
    AGENTSCOPE;

    public static ExecutionRuntime from(String value) {
        if (value == null || value.isBlank()) {
            return QWENPAW;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Unsupported execution runtime: " + value.trim()
                            + "; expected QWENPAW or AGENTSCOPE",
                    error);
        }
    }
}
