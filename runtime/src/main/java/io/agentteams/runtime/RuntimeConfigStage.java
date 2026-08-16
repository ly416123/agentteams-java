package io.agentteams.runtime;

import java.util.Objects;
import java.util.UUID;

/** Opaque handle for one staged configuration activation. */
public record RuntimeConfigStage(UUID id, RuntimeConfigSnapshot snapshot) {
    public RuntimeConfigStage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
