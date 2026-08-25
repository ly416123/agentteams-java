package io.agentteams.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, fail-closed AgentScope rollout decision at the runtime boundary. */
public final class AgentScopeRolloutPolicy {
    public static final String QWENPAW = "QWENPAW";
    public static final String AGENTSCOPE = "AGENTSCOPE";

    private final String defaultRuntime;
    private final boolean enabled;
    private final int rolloutPercentage;
    private final Set<String> agentAllowlist;
    private final Set<String> teamAllowlist;
    private final Set<String> tenantAllowlist;

    public AgentScopeRolloutPolicy(String defaultRuntime, boolean enabled, int rolloutPercentage,
            Set<String> agentAllowlist, Set<String> teamAllowlist, Set<String> tenantAllowlist) {
        this.defaultRuntime = normalizeRuntime(defaultRuntime);
        if (rolloutPercentage < 0 || rolloutPercentage > 100) {
            throw new IllegalArgumentException("rolloutPercentage must be between 0 and 100");
        }
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.agentAllowlist = immutableIds(agentAllowlist, "agentAllowlist");
        this.teamAllowlist = immutableIds(teamAllowlist, "teamAllowlist");
        this.tenantAllowlist = immutableIds(tenantAllowlist, "tenantAllowlist");
    }

    public static AgentScopeRolloutPolicy fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new AgentScopeRolloutPolicy(
                environment.getOrDefault("AGENTTEAMS_RUNTIME_DEFAULT", QWENPAW),
                booleanValue(environment.getOrDefault("AGENTTEAMS_AGENTSCOPE_ENABLED", "false")),
                integerValue(environment.getOrDefault("AGENTTEAMS_AGENTSCOPE_ROLLOUT_PERCENTAGE", "0")),
                csv(environment.get("AGENTTEAMS_AGENTSCOPE_AGENT_ALLOWLIST")),
                csv(environment.get("AGENTTEAMS_AGENTSCOPE_TEAM_ALLOWLIST")),
                csv(environment.get("AGENTTEAMS_AGENTSCOPE_TENANT_ALLOWLIST")));
    }

    public String select(Map<String, String> scope) {
        Objects.requireNonNull(scope, "scope");
        if (!enabled) return defaultRuntime;
        if (matches(scope, "agentId", agentAllowlist)
                || matches(scope, "teamId", teamAllowlist)
                || matches(scope, "tenantId", tenantAllowlist)) {
            return AGENTSCOPE;
        }
        if (rolloutPercentage == 0) return defaultRuntime;
        String stableKey = firstScopeValue(scope, "agentId", "teamId", "tenantId", "taskId");
        if (stableKey == null) return defaultRuntime;
        return bucket(stableKey) < rolloutPercentage ? AGENTSCOPE : defaultRuntime;
    }

    public String defaultRuntime() { return defaultRuntime; }
    public boolean enabled() { return enabled; }
    public int rolloutPercentage() { return rolloutPercentage; }

    private static boolean matches(Map<String, String> scope, String field, Set<String> allowlist) {
        String value = scope.get(field);
        return value != null && allowlist.contains(value.trim());
    }

    private static String firstScopeValue(Map<String, String> scope, String... fields) {
        return Arrays.stream(fields).map(scope::get)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).findFirst().orElse(null);
    }

    private static int bucket(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            int unsigned = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
            return unsigned % 100;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String normalizeRuntime(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!QWENPAW.equals(normalized) && !AGENTSCOPE.equals(normalized)) {
            throw new IllegalArgumentException("defaultRuntime must be QWENPAW or AGENTSCOPE");
        }
        return normalized;
    }

    private static Set<String> immutableIds(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not contain blank values");
            }
            normalized.add(value.trim());
        }
        return Set.copyOf(normalized);
    }

    private static boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("boolean configuration must be true or false");
    }

    private static int integerValue(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("rollout percentage must be an integer", error);
        }
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.copyOf(Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList());
    }
}
