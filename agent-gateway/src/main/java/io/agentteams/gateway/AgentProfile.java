package io.agentteams.gateway;

import java.util.Map;
import java.util.Objects;

/** Identity and advertised capabilities received during a successful Hello. */
public record AgentProfile(
        String agentId,
        String runtime,
        String runtimeVersion,
        Map<String, String> capabilities) {

    public AgentProfile {
        requireText(agentId, "agentId");
        requireText(runtime, "runtime");
        requireText(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Map.copyOf(capabilities);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
