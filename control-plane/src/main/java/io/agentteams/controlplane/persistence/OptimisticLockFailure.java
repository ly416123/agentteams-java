package io.agentteams.controlplane.persistence;

import java.util.UUID;

public final class OptimisticLockFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String aggregateType;
    private final UUID aggregateId;
    private final long expectedVersion;
    private final long actualVersion;

    public OptimisticLockFailure(String aggregateType, UUID aggregateId,
            long expectedVersion, long actualVersion) {
        super("Optimistic lock failed for " + aggregateType + " " + aggregateId
                + ": expected version " + expectedVersion + " but was " + actualVersion);
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long actualVersion() {
        return actualVersion;
    }
}
