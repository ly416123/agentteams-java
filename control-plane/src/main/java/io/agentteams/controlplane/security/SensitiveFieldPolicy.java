package io.agentteams.controlplane.security;

import java.util.Set;

/** Shared field-name policy for API projections that may contain credentials. */
public final class SensitiveFieldPolicy {
    private static final Set<String> EXACT = Set.of("token", "accesstoken", "refreshtoken", "bearertoken",
            "secret", "clientsecret", "password", "authorization", "credential", "credentials", "privatekey",
            "containerlog", "log", "logs", "spec", "input");

    private SensitiveFieldPolicy() { }

    public static boolean isSensitive(String fieldName) {
        String normalized = normalize(fieldName);
        return EXACT.contains(normalized)
                || normalized.contains("apikey")
                || normalized.contains("secret")
                || normalized.endsWith("token")
                || normalized.contains("credential")
                || normalized.endsWith("spec")
                || normalized.endsWith("input");
    }

    public static String normalize(String fieldName) {
        return fieldName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
