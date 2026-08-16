package io.agentteams.domain.task;

public final class StaleTaskVersionException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long expectedVersion;
    private final long actualVersion;

    public StaleTaskVersionException(long expectedVersion, long actualVersion) {
        super("Stale task version: expected " + expectedVersion + " but was " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
