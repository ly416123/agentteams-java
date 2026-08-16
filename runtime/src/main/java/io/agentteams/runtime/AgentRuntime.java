package io.agentteams.runtime;

import java.util.Optional;
import java.util.UUID;

public interface AgentRuntime {
    void start(AgentRuntimeContext context);

    RuntimeSubmission submit(RuntimeTask task);

    CompletionStatus complete(RuntimeResult result);

    boolean cancel(UUID taskId);

    Optional<RuntimeStatus> status(UUID taskId);

    RuntimeSnapshot snapshot();

    void stop();
}
