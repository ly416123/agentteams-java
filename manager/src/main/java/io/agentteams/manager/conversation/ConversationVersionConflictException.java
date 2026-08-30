package io.agentteams.manager.conversation;

import java.util.UUID;

public final class ConversationVersionConflictException extends RuntimeException {
    private final UUID sessionId;
    private final long expectedVersion;
    private final long actualVersion;

    public ConversationVersionConflictException(UUID sessionId, long expectedVersion, long actualVersion) {
        super("conversation version does not match");
        this.sessionId = sessionId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public UUID sessionId() { return sessionId; }
    public long expectedVersion() { return expectedVersion; }
    public long actualVersion() { return actualVersion; }
}
