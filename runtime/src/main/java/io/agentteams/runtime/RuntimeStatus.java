package io.agentteams.runtime;

import java.util.Objects;
import java.util.Optional;

public record RuntimeStatus(RuntimeTask task, RuntimeTaskState state, RuntimeResult resultValue) {
    public RuntimeStatus {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(state, "state");
        if (state == RuntimeTaskState.RUNNING && resultValue != null) {
            throw new IllegalArgumentException("running task must not have a result");
        }
    }

    public Optional<RuntimeResult> result() {
        return Optional.ofNullable(resultValue);
    }
}
