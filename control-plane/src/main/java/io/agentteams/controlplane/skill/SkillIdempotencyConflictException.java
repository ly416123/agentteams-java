package io.agentteams.controlplane.skill;

public final class SkillIdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SkillIdempotencyConflictException(String key, String operation) {
        super("Idempotency key '" + key + "' was already used for a different " + operation + " request");
    }
}
