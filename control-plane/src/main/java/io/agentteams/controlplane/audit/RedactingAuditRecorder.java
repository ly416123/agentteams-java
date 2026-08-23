package io.agentteams.controlplane.audit;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Removes credentials and bearer material before an audit event reaches a sink. */
public final class RedactingAuditRecorder implements AuditRecorder {
    private static final Set<String> SENSITIVE = Set.of("apikey", "token", "accesstoken", "refreshtoken", "idtoken",
            "authorization", "bearertoken", "clientsecret", "secret", "password", "credential", "jwt");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|token|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|"
                    + "secret|password|authorization|jwt)\\b\\s*[:=]\\s*)(?!Bearer\\b)([^\\s,;&]+)");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])");
    private final AuditRecorder delegate;

    public RedactingAuditRecorder(AuditRecorder delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }

    @Override
    public void record(AuditEvent event) {
        Map<String, String> redacted = redactAttributes(event.attributes());
        delegate.record(new AuditEvent(event.id(), event.actor(), event.action(), event.resourceType(),
                event.resourceId(), redacted, event.occurredAt()));
    }

    static Map<String, String> redactAttributes(Map<String, String> attributes) {
        Map<String, String> redacted = new java.util.LinkedHashMap<>();
        attributes.forEach((key, value) -> redacted.put(key, isSensitiveKey(key) ? "[REDACTED]" : redactValue(value)));
        return redacted;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE.stream().anyMatch(sensitive -> normalized.equals(sensitive.replace("_", "")));
    }

    private static String redactValue(String value) {
        String redacted = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        redacted = KEY_VALUE.matcher(redacted).replaceAll("$1[REDACTED]");
        return JWT.matcher(redacted).replaceAll("[REDACTED]");
    }
}
