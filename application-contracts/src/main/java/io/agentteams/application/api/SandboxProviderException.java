package io.agentteams.application.api;

import java.util.Objects;

/** Sanitized exception boundary for provider failures. */
public class SandboxProviderException extends RuntimeException {
    private final SandboxFailureCategory category;

    public SandboxProviderException(SandboxFailureCategory category, String message) {
        this(category, message, null);
    }

    public SandboxProviderException(SandboxFailureCategory category, String message, Throwable cause) {
        super(FailureMessageSanitizer.redact(Objects.requireNonNull(message, "message must not be null")), cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public SandboxFailureCategory category() {
        return category;
    }

    public SandboxFailure failure() {
        return new SandboxFailure(category, getMessage());
    }
}
