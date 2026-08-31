package io.agentteams.controlplane.token;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only token accounting fact. It deliberately excludes prompt and response content. */
public record TokenLedgerEntry(UUID id, UUID reservationId, TokenLedgerScope scope, UUID taskId, UUID runId,
        Kind kind, long tokens, String operationKey, String source, String model, Instant occurredAt) {
    public TokenLedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (tokens < 0) throw new IllegalArgumentException("tokens must not be negative");
        operationKey = required(operationKey, "operationKey");
        source = safeAttribution(source, "source");
        model = safeAttribution(model, "model");
    }

    public enum Kind { RESERVED, SETTLED, RELEASED }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    static String safeAttribution(String value, String field) {
        String normalized = required(value, field);
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 255 || normalized.chars().anyMatch(Character::isISOControl)
                || lower.contains("prompt") || lower.contains("secret") || lower.contains("credential")
                || lower.contains("password") || lower.contains("authorization")) {
            throw new IllegalArgumentException(field + " contains sensitive or invalid content");
        }
        return normalized;
    }
}
