package io.agentteams.application.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Redacts common credential-shaped values before failures cross module boundaries. */
public final class FailureMessageSanitizer {
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|token|secret|api[-_]?key)([\\\"']?)(\\s*[:=]\\s*)"
                    + "(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;&?#}]+)");

    private FailureMessageSanitizer() {
    }

    public static String redact(String value) {
        String message = Objects.requireNonNullElse(value, "");
        return SECRET_PATTERN.matcher(message).replaceAll(match -> {
            String secret = match.group(4);
            String replacement = secret.startsWith("\"")
                    ? "\"[REDACTED]\""
                    : secret.startsWith("'") ? "'[REDACTED]'" : "[REDACTED]";
            return match.group(1) + match.group(2) + match.group(3) + replacement;
        });
    }
}
