package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTypeTest {
    @Test
    void taskTypeIsStableAndExtensibleWithoutDatabaseEnumChanges() {
        TaskRecord task = new TaskRecord(UUID.randomUUID(), "scheduled", "report", TaskPhase.DRAFT, 0,
                "{}", "scheduler", "schedule", null, null, Instant.EPOCH, Instant.EPOCH, 0, "SCHEDULED");

        assertThat(task.taskType()).isEqualTo("SCHEDULED");
    }
}
