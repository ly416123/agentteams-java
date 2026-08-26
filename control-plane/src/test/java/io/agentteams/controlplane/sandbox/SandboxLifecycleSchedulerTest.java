package io.agentteams.controlplane.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SandboxLifecycleSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void nonLeaderDoesNotRunSandboxLifecycleBatch() {
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("sandbox-lifecycle", "cp-2", NOW, Duration.ofSeconds(30)))
                .thenReturn(false);
        SandboxLifecycleService lifecycle = mock(SandboxLifecycleService.class);
        SandboxLifecycleScheduler scheduler = new SandboxLifecycleScheduler(lifecycle,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-2",
                Duration.ofSeconds(30), new SandboxRuntimeProperties());

        SandboxLifecycleScheduler.RunResult result = scheduler.runOnce();

        assertThat(result.leader()).isFalse();
        verify(lifecycle, never()).provisionRequested(NOW, 16);
    }

    @Test
    void leaderRunsLifecycleStagesInOneBatch() {
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("sandbox-lifecycle", "cp-1", NOW, Duration.ofSeconds(30)))
                .thenReturn(true);
        SandboxLifecycleService lifecycle = mock(SandboxLifecycleService.class);
        SandboxRuntimeProperties properties = new SandboxRuntimeProperties();
        properties.setBatchSize(4);
        SandboxLifecycleScheduler scheduler = new SandboxLifecycleScheduler(lifecycle,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30), properties);

        SandboxLifecycleScheduler.RunResult result = scheduler.runOnce();

        assertThat(result.leader()).isTrue();
        verify(lifecycle).provisionRequested(NOW, 4);
        verify(lifecycle).observeActive(NOW, 4);
        verify(lifecycle).renewExpiring(NOW, properties.getRenewBefore(), properties.getRenewExtension(), 4);
        verify(lifecycle).expire(NOW, 4);
        verify(lifecycle).terminateStopping(NOW, 4);
        verify(lifecycle).observeStopping(NOW, 4);
    }
}
