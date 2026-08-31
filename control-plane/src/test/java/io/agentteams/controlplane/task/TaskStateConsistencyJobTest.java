package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TaskStateConsistencyJobTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void scansOnlyWhenThisReplicaOwnsTheLease() {
        TaskStateConsistencyService service = mock(TaskStateConsistencyService.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("task-state-consistency", "pod-a", NOW, Duration.ofSeconds(30))).thenReturn(true);
        when(service.reconcile(NOW, Duration.ofHours(1), 100))
                .thenReturn(new TaskStateConsistencyService.ReconcileResult(3, 2, 1, 0));

        TaskStateConsistencyJob job = new TaskStateConsistencyJob(service, new SchedulerLeaseService(leases),
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-a", Duration.ofSeconds(30), Duration.ofHours(1), 100);

        assertThat(job.runOnce()).isEqualTo(new TaskStateConsistencyJob.RunResult(true, 3, 2, 1, 0));
        verify(leases).release("task-state-consistency", "pod-a", NOW);
        verify(service).reconcile(NOW, Duration.ofHours(1), 100);
    }

    @Test
    void returnsNoWorkWhenAnotherReplicaOwnsTheLease() {
        TaskStateConsistencyService service = mock(TaskStateConsistencyService.class);
        SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
        when(leases.run(eq("task-state-consistency"), eq("pod-a"), eq(NOW), eq(Duration.ofSeconds(30)),
                any(Supplier.class))).thenReturn(new SchedulerLeaseService.Result<>(false, null));

        TaskStateConsistencyJob job = new TaskStateConsistencyJob(service, leases,
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-a", Duration.ofSeconds(30), Duration.ofHours(1), 100);

        assertThat(job.runOnce()).isEqualTo(new TaskStateConsistencyJob.RunResult(false, 0, 0, 0, 0));
    }
}
