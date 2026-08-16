package io.agentteams.controlplane.persistence;

public final class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String key, String operation) {
        super("Idempotency key '" + key + "' was already used for a different " + operation + " request");
    }
}
