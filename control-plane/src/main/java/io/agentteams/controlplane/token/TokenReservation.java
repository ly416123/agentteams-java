package io.agentteams.controlplane.token;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Mutable projection of one token reservation; its facts live in TokenLedgerEntry rows. */
public record TokenReservation(UUID id, TokenLedgerScope scope, UUID taskId, UUID runId,
        long estimatedTokens, long settledTokens, State state, String reserveIdempotencyKey,
        String settleIdempotencyKey, String releaseIdempotencyKey, Instant createdAt, Instant updatedAt) {
    public TokenReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
        if (settledTokens < 0) throw new IllegalArgumentException("settledTokens must not be negative");
        if (state == State.RESERVED && settledTokens != 0) {
            throw new IllegalArgumentException("reserved token reservation cannot have settled tokens");
        }
        reserveIdempotencyKey = required(reserveIdempotencyKey, "reserveIdempotencyKey");
        settleIdempotencyKey = optional(settleIdempotencyKey);
        releaseIdempotencyKey = optional(releaseIdempotencyKey);
    }

    public TokenReservation transition(State next, long actualTokens, String operationKey, Instant at) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        if (state != State.RESERVED) throw new TokenLedgerConflictException("reservation is terminal");
        if (actualTokens < 0) throw new IllegalArgumentException("settledTokens must not be negative");
        if (next == State.SETTLED) {
            return new TokenReservation(id, scope, taskId, runId, estimatedTokens, actualTokens, next,
                    reserveIdempotencyKey, operationKey, null, createdAt, at);
        }
        if (next == State.RELEASED) {
            if (actualTokens != 0) throw new IllegalArgumentException("released reservation cannot settle tokens");
            return new TokenReservation(id, scope, taskId, runId, estimatedTokens, 0, next,
                    reserveIdempotencyKey, null, operationKey, createdAt, at);
        }
        throw new IllegalArgumentException("unsupported token reservation state: " + next);
    }

    public enum State { RESERVED, SETTLED, RELEASED }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
