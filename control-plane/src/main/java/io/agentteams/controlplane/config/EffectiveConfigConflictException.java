package io.agentteams.controlplane.config;

/** Stable error raised when an overlay attempts to weaken a security boundary. */
public final class EffectiveConfigConflictException extends IllegalArgumentException {
    private final String code;

    public EffectiveConfigConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
