package io.agentteams.application.api;

import java.util.Objects;

final class ProtocolValidation {
    private ProtocolValidation() {
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static <T> T require(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }
}
