package io.agentteams.controlplane.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledTaskSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:05:00Z");
    private static final ScheduledTaskScope SCOPE = new ScheduledTaskScope("org-1", "tenant-1", "project-1");

    @Test
    void leaderCreatesOneIdempotentTaskAndAdvancesSchedule() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        TaskService tasks = mock(TaskService.class);
        UUID scheduleId = UUID.randomUUID();
        ScheduledTaskDefinition schedule = new ScheduledTaskDefinition(scheduleId, "report", SCOPE,
                "0 0/5 * * * *", "UTC", "Report", "desc", "{\"kind\":\"report\"}", "manager", "scheduler",
                true, NOW, null, null, 0, NOW.minusSeconds(300), NOW.minusSeconds(300));
        UUID taskId = UUID.randomUUID();
        when(repository.findDue(NOW, 10)).thenReturn(List.of(schedule));
        when(leases.tryAcquire("scheduled-tasks", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(true);
        when(tasks.create(eq("schedule:" + scheduleId + ":" + NOW), any(TaskService.TaskInput.class)))
                .thenReturn(TaskRecord.draft(taskId, "Report", "desc", "manager", "scheduler", NOW));
        when(repository.advance(scheduleId, NOW, taskId, Instant.parse("2026-08-31T10:10:00Z"), NOW))
                .thenReturn(true);

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(repository, tasks,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new ScheduledTaskScheduler.RunResult(true, 1, 0));
        verify(repository).advance(scheduleId, NOW, taskId, Instant.parse("2026-08-31T10:10:00Z"), NOW);
        verify(leases).release("scheduled-tasks", "cp-1", NOW);
    }

    @Test
    void nonLeaderDoesNotTouchSchedulesOrTasks() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        TaskService tasks = mock(TaskService.class);
        when(leases.tryAcquire("scheduled-tasks", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(false);

        ScheduledTaskScheduler scheduler = new ScheduledTaskScheduler(repository, tasks,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new ScheduledTaskScheduler.RunResult(false, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(repository, tasks);
    }
}
