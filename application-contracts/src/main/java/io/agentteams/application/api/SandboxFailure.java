package io.agentteams.application.api;

import java.util.Objects;

/** Redacted, low-cardinality provider failure carried by an observation. */
public record SandboxFailure(SandboxFailureCategory category, String message) {

    private static final int MAX_MESSAGE_LENGTH = 512;

    public SandboxFailure {
        Objects.requireNonNull(category, "category must not be null");
        message = FailureMessageSanitizer.redact(Objects.requireNonNullElse(message, ""));
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
    }
}
