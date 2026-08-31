package io.agentteams.controlplane.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledTaskRepository {
    ScheduledTaskDefinition insert(ScheduledTaskDefinition definition);

    Optional<ScheduledTaskDefinition> find(ScheduledTaskScope scope, UUID id);

    List<ScheduledTaskDefinition> list(ScheduledTaskScope scope);

    List<ScheduledTaskDefinition> findDue(Instant now, int limit);

    ScheduledTaskDefinition transition(ScheduledTaskScope scope, UUID id, boolean expectedEnabled,
            boolean nextEnabled, String operationKey, Instant now);

    ScheduledTaskDefinition resume(ScheduledTaskScope scope, UUID id, String operationKey,
            Instant nextRunAt, Instant now);

    boolean advance(UUID id, Instant dueAt, UUID taskId, Instant nextRunAt, Instant now);
}
