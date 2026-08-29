package io.agentteams.controlplane.security;

import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{3,}");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|deepseek[_-]?api[_-]?key|provider[_-]?api[_-]?key|"
                    + "client[_-]?secret|password|token|secret|private[_-]?key|credentials?)"
                    + "\\s*[:=]\\s*[\\\"']?[A-Za-z0-9._~+/=-]{3,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?i)-----BEGIN .*PRIVATE KEY-----");
    private static final Pattern API_TOKEN = Pattern.compile("(?i)\\b(?:sk|rk)-[A-Za-z0-9_-]{3,}\\b");

    /** Detects credential-shaped values even when a producer puts them in a benign field. */
    public static boolean containsCredential(String value) {
        return value != null && (BEARER.matcher(value).find() || KEY_VALUE.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find() || API_TOKEN.matcher(value).find());
    }

    public static String normalize(String fieldName) {
        return fieldName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
