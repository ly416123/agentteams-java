package io.agentteams.controlplane.task;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

/** Scoped persistence boundary for search provenance. */
public interface TaskSourceEvidenceRepository {
    UUID activeRun(ExecutionContext context, UUID taskId);
    boolean isActiveExecution(UUID taskId, UUID attemptId, UUID leaseId, Instant at);
    void save(ExecutionContext context, UUID taskId, UUID runId, UUID attemptId, String callId,
            String provider, String idempotencyKey, List<TaskSourceEvidence.Source> sources);
    TaskSourceEvidence find(ExecutionContext context, UUID taskId, UUID runId, int callLimit, int sourceLimit);
}
