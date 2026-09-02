package io.agentteams.controlplane.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledTaskRunRepository {
    ScheduledTaskRun insertIfAbsent(ScheduledTaskRun run);
    List<ScheduledTaskRun> list(ScheduledTaskScope scope, UUID scheduleId, int limit);
    Optional<ScheduledTaskRun> find(ScheduledTaskScope scope, UUID scheduleId, UUID runId);
    ScheduledTaskRun cancel(ScheduledTaskScope scope, UUID scheduleId, UUID runId, String operationKey, Instant at);
}
