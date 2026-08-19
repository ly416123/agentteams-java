package io.agentteams.runtime;

import java.util.UUID;

public interface QwenPawProcessPort {
    void start(AgentRuntimeContext context, RuntimeResultSink resultSink);

    void submit(RuntimeTask task);

    void cancel(UUID taskId);

    /** Optional runtime-specific configuration hook. */
    default void applyConfig(RuntimeConfigSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
    }

    void stop();
}
