package io.agentteams.worker.agentscope;

/** Stable failure raised by the active workspace gate. */
public final class SandboxWorkspaceException extends IllegalStateException {
    public enum Reason {
        OWNER_MISMATCH,
        EXPIRED,
        LOST,
        INACTIVE,
        UNAVAILABLE
    }

    private final Reason reason;

    public SandboxWorkspaceException(Reason reason) {
        super("sandbox workspace is not active: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
