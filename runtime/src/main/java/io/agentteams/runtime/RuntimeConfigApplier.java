package io.agentteams.runtime;

import java.util.Optional;

/** Runtime-specific staging hook hidden behind the neutral config protocol. */
@FunctionalInterface
public interface RuntimeConfigApplier {
    RuntimeConfigPrepared stage(RuntimeConfigSnapshot snapshot, Optional<RuntimeConfigSnapshot> current);
}
