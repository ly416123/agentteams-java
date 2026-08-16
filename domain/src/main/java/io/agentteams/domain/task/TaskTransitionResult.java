package io.agentteams.domain.task;

import java.util.UUID;

public sealed interface TaskTransitionResult permits AppliedTransition, DuplicateTransition {

    UUID eventId();

    Task task();

    default long version() {
        return task().version();
    }
}
