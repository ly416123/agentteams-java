package io.agentteams.domain.task;

public enum TaskPhase {
    DRAFT,
    QUEUED,
    PAUSED,
    ASSIGNED,
    ACCEPTED,
    RUNNING,
    WAITING_SUBTASKS,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == REJECTED;
    }
}
