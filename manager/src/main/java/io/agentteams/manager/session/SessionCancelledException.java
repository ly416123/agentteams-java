package io.agentteams.manager.session;

public final class SessionCancelledException extends RuntimeException {
    public SessionCancelledException() { super("session is cancelled"); }
}
