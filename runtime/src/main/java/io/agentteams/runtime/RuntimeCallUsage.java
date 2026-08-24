package io.agentteams.runtime;

import java.util.Objects;

/** Content-free model call usage returned by a runtime adapter. */
public record RuntimeCallUsage(String provider, String model, long latencyMillis,
        long promptTokens, long completionTokens) {
    public RuntimeCallUsage {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        if (latencyMillis < 0 || promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("runtime usage values must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
