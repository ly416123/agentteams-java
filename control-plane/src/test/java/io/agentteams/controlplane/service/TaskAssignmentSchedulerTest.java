package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.task.SubtaskGateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskAssignmentSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void repairsExpiredLeasesAndAssignsQueuedTasksWhileHoldingLeaderLease() {
        SchedulerLeaseRepository repository = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        TaskAssignmentService assignments = org.mockito.Mockito.mock(TaskAssignmentService.class);
        SubtaskGateService gate = org.mockito.Mockito.mock(SubtaskGateService.class);
        when(repository.tryAcquire("task-assignment", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(true);
        UUID taskId = UUID.randomUUID();
        when(assignments.recoverExpiredLeases(NOW)).thenReturn(2);
        when(assignments.queuedTaskIds(16, NOW, 0L)).thenReturn(List.of(taskId));
        when(gate.tick(16)).thenReturn(1);

        TaskAssignmentScheduler scheduler = new TaskAssignmentScheduler(assignments, gate,
                new SchedulerLeaseService(repository), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30), 16);

        assertThat(scheduler.runOnce()).isEqualTo(new TaskAssignmentScheduler.RunResult(2, 1, 0, 1));
        verify(assignments).queueReadyTask(taskId, NOW);
        verify(repository).release("task-assignment", "cp-1", NOW);
    }

    @Test
    void skipsWorkWhenAnotherReplicaOwnsTheLease() {
        SchedulerLeaseRepository repository = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        TaskAssignmentService assignments = org.mockito.Mockito.mock(TaskAssignmentService.class);
        SubtaskGateService gate = org.mockito.Mockito.mock(SubtaskGateService.class);
        when(repository.tryAcquire("task-assignment", "cp-2", NOW, Duration.ofSeconds(30))).thenReturn(false);

        TaskAssignmentScheduler scheduler = new TaskAssignmentScheduler(assignments, gate,
                new SchedulerLeaseService(repository), Clock.fixed(NOW, ZoneOffset.UTC), "cp-2",
                Duration.ofSeconds(30), 16);

        assertThat(scheduler.runOnce()).isEqualTo(new TaskAssignmentScheduler.RunResult(0, 0, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(assignments);
    }

    @Test
    void rotatesQueuedTaskPagesWhenEarlierTasksHaveNoCapacity() {
        SchedulerLeaseRepository repository = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        TaskAssignmentService assignments = org.mockito.Mockito.mock(TaskAssignmentService.class);
        SubtaskGateService gate = org.mockito.Mockito.mock(SubtaskGateService.class);
        when(repository.tryAcquire("task-assignment", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(assignments.queuedTaskIds(2, NOW, 0L)).thenReturn(List.of(first, second));
        when(assignments.queuedTaskIds(2, NOW, 2L)).thenReturn(List.of(third));
        doThrow(new IllegalStateException("no READY agent matches task capabilities"))
                .when(assignments).queueReadyTask(first, NOW);
        doThrow(new IllegalStateException("no READY agent matches task capabilities"))
                .when(assignments).queueReadyTask(second, NOW);
        when(gate.tick(2)).thenReturn(0);

        TaskAssignmentScheduler scheduler = new TaskAssignmentScheduler(assignments, gate,
                new SchedulerLeaseService(repository), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30), 2);

        assertThat(scheduler.runOnce()).isEqualTo(new TaskAssignmentScheduler.RunResult(0, 0, 2, 0));
        assertThat(scheduler.runOnce()).isEqualTo(new TaskAssignmentScheduler.RunResult(0, 1, 0, 0));

        verify(assignments).queuedTaskIds(2, NOW, 0L);
        verify(assignments).queuedTaskIds(2, NOW, 2L);
        verify(assignments).queueReadyTask(third, NOW);
    }
}
