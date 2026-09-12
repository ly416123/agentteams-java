package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskFileReconciliationJobTest {
    private final TaskFileService service = mock(TaskFileService.class);
    private final SchedulerLeaseService lease = mock(SchedulerLeaseService.class);
    private TaskFileReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new TaskFileReconciliationJob(service, lease, Clock.systemUTC(), "owner",
                Duration.ofSeconds(30), 200);
    }

    @Test
    void runOnceMapsLeaderAndMarkedCount() {
        when(lease.run(eq("task-file-reconciliation"), eq("owner"), any(Instant.class),
                any(Duration.class), any(Supplier.class)))
                .thenReturn(new SchedulerLeaseService.Result<>(true, 3));

        TaskFileReconciliationJob.RunResult result = job.runOnce();

        assertThat(result.leader()).isTrue();
        assertThat(result.markedMissing()).isEqualTo(3);
    }

    @Test
    void runOnceReportsNonLeaderWithoutWork() {
        when(lease.run(eq("task-file-reconciliation"), eq("owner"), any(Instant.class),
                any(Duration.class), any(Supplier.class)))
                .thenReturn(new SchedulerLeaseService.Result<>(false, null));

        TaskFileReconciliationJob.RunResult result = job.runOnce();

        assertThat(result.leader()).isFalse();
        assertThat(result.markedMissing()).isZero();
    }
}
