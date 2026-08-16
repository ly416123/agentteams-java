package io.agentteams.domain.task;

public enum TaskPhase {
    DRAFT,
    QUEUED,
    ASSIGNED,
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
