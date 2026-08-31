package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence seam for the execution-run projection used by process observations. */
public interface TaskRunObservationRepository {
    Optional<ExecutionContext> contextForTask(UUID taskId);

    Optional<TaskPlanningSnapshot> planningForTask(UUID taskId);

    void ensureRun(ExecutionContext context, UUID taskId, UUID runId, String status, Instant at);

    long nextSequence(UUID runId);

    record TaskPlanningSnapshot(String title, String description, String source, String specJson) {
        public TaskPlanningSnapshot {
            requireText(title, "title");
            description = description == null ? "" : description;
            requireText(source, "source");
            requireText(specJson, "specJson");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
