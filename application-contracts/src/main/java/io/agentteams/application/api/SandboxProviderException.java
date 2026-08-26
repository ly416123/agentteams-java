package io.agentteams.application.api;

import java.util.Objects;

/** Sanitized exception boundary for provider failures. */
public class SandboxProviderException extends RuntimeException {
    private static final int MAX_MESSAGE_LENGTH = 512;
    private final SandboxFailureCategory category;

    public SandboxProviderException(SandboxFailureCategory category, String message) {
        this(category, message, null);
    }

    public SandboxProviderException(SandboxFailureCategory category, String message, Throwable cause) {
        super(redactAndLimit(message), cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public SandboxFailureCategory category() {
        return category;
    }

    public SandboxFailure failure() {
        return new SandboxFailure(category, getMessage());
    }

    private static String redactAndLimit(String message) {
        String sanitized = FailureMessageSanitizer.redact(
                Objects.requireNonNull(message, "message must not be null"));
        return sanitized.length() > MAX_MESSAGE_LENGTH ? sanitized.substring(0, MAX_MESSAGE_LENGTH) : sanitized;
    }
}
