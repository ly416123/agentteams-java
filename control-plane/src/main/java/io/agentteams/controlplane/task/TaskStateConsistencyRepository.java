package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence boundary for consistency snapshots and durable findings. */
public interface TaskStateConsistencyRepository {
    List<TaskStateConsistencySnapshot> findSnapshots(Instant since, int limit);

    void upsertIssue(TaskStateConsistencyIssue issue, Instant observedAt);

    List<String> findOpenIssueTypes(UUID taskId, UUID runId);

    void resolveIssue(UUID taskId, UUID runId, String type, Instant resolvedAt);

    List<TaskStateConsistencyIssueRecord> findOpenIssues(int limit);
}
