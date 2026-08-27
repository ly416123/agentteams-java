package io.agentteams.gateway;

import java.util.Map;
import java.util.Objects;

/** Identity and advertised capabilities received during a successful Hello. */
public record AgentProfile(
        String agentId,
        String runtime,
        String runtimeVersion,
        Map<String, String> capabilities,
        String specDigest,
        String configRevision,
        String secretGeneration) {

    public AgentProfile(String agentId, String runtime, String runtimeVersion,
            Map<String, String> capabilities) {
        this(agentId, runtime, runtimeVersion, capabilities, "", "", "");
    }

    public AgentProfile {
        requireText(agentId, "agentId");
        requireText(runtime, "runtime");
        requireText(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Map.copyOf(capabilities);
        specDigest = optionalText(specDigest, "specDigest");
        configRevision = optionalText(configRevision, "configRevision");
        secretGeneration = optionalText(secretGeneration, "secretGeneration");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException(field + " must not exceed 512 characters");
        }
        return normalized;
    }
}
