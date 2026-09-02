package io.agentteams.controlplane.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRecoveryCheckpointRepository {
    TaskRecoveryCheckpoint save(TaskRecoveryCheckpoint checkpoint);
    List<TaskRecoveryCheckpoint> findByRun(UUID runId);
    Optional<TaskRecoveryCheckpoint> findLatestByTask(UUID taskId);
}
