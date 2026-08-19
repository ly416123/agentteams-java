package io.agentteams.runtime;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public interface AgentRuntime {
    void start(AgentRuntimeContext context);

    RuntimeSubmission submit(RuntimeTask task);

    CompletionStatus complete(RuntimeResult result);

    boolean cancel(UUID taskId);

    Optional<RuntimeStatus> status(UUID taskId);

    RuntimeSnapshot snapshot();

    /** Applies a validated immutable configuration snapshot to the runtime. */
    default void applyConfig(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    void stop();
}
