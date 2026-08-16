package io.agentteams.domain.task;

import java.util.Objects;
import java.util.UUID;

public record AppliedTransition(UUID eventId, Task task, TaskPhase fromPhase, TaskPhase toPhase)
        implements TaskTransitionResult {

    public AppliedTransition {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(fromPhase, "fromPhase");
        Objects.requireNonNull(toPhase, "toPhase");
    }
}
