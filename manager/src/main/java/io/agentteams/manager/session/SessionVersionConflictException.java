package io.agentteams.manager.session;

import java.util.UUID;

public final class SessionVersionConflictException extends RuntimeException {
    private final UUID sessionId;
    private final long expectedVersion;
    private final long actualVersion;

    public SessionVersionConflictException(UUID sessionId, long expectedVersion, long actualVersion) {
        super("session version does not match");
        this.sessionId = sessionId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID sessionId() { return sessionId; }
    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
