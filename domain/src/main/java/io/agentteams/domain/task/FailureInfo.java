package io.agentteams.domain.task;

import java.util.Objects;
import java.util.regex.Pattern;

/** A failure code and message safe to expose in domain events and persistence. */
public record FailureInfo(String code, String redactedMessage) {

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(password|token|secret|api[-_]?key)([\\\"']?)(\\s*[:=]\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;&?#}]+)");

    public FailureInfo {
        code = requireText(code, "code");
        redactedMessage = Objects.requireNonNullElse(redactedMessage, "");
    }

    public static FailureInfo fromRaw(String code, String rawMessage) {
        String message = Objects.requireNonNullElse(rawMessage, "");
        String redacted = SECRET_PATTERN.matcher(message).replaceAll(match -> {
            String value = match.group(4);
            String replacement = value.startsWith("\"")
                    ? "\"[REDACTED]\""
                    : value.startsWith("'") ? "'[REDACTED]'" : "[REDACTED]";
            return match.group(1) + match.group(2) + match.group(3) + replacement;
        });
        return new FailureInfo(code, redacted);
    }

    public static FailureInfo redacted(String code, String redactedMessage) {
        return new FailureInfo(code, redactedMessage);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
