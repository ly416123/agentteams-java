package io.agentteams.domain.task;

import java.util.Objects;
import java.util.UUID;

public record DuplicateTransition(UUID eventId, Task task) implements TaskTransitionResult {

    public DuplicateTransition {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(task, "task");
    }
}
