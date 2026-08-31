package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable store for terminal task result manifests. */
public interface TaskResultManifestRepository {
    void upsert(ExecutionContext context, TaskResultManifest manifest);

    Optional<TaskResultManifest> find(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible);
}
