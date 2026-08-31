package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence seam for the execution-run projection used by process observations. */
public interface TaskRunObservationRepository {
    Optional<ExecutionContext> contextForTask(UUID taskId);

    void ensureRun(ExecutionContext context, UUID taskId, UUID runId, String status, Instant at);

    long nextSequence(UUID runId);
}
