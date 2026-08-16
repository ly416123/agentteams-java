package io.agentteams.manager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable, content-free record of one model call. */
public record ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
        String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt) {
    public enum Outcome { SUCCESS, FAILURE }

    public record TokenUsage(long promptTokens, long completionTokens) {
        public TokenUsage {
            if (promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("token usage must not be negative");
            }
        }
    }

    public ModelCallAudit {
        requireText(provider, "provider");
        requireText(model, "model");
        Objects.requireNonNull(latency, "latency");
        if (latency.isNegative()) throw new IllegalArgumentException("latency must not be negative");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        requireText(requestHash, "requestHash");
        if (responseHash != null && responseHash.isBlank()) {
            throw new IllegalArgumentException("responseHash must not be blank when present");
        }
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
