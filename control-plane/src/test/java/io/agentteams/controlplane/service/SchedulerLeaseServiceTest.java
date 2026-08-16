package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SchedulerLeaseServiceTest {
    @Test
    void onlyLeaderRunsWorkAndAlwaysReleasesLease() {
        SchedulerLeaseRepository repository = mock(SchedulerLeaseRepository.class);
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        when(repository.tryAcquire("task-scheduler", "pod-a", now, Duration.ofSeconds(30))).thenReturn(true);
        SchedulerLeaseService service = new SchedulerLeaseService(repository);
        assertThat(service.run("task-scheduler", "pod-a", now, Duration.ofSeconds(30), () -> 7).value()).isEqualTo(7);
        verify(repository).release("task-scheduler", "pod-a", now);
    }
}
