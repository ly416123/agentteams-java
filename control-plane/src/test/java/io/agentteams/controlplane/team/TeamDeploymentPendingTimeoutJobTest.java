package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutJob.RunResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TeamDeploymentPendingTimeoutJobTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void failsMembersOnlyWhenThisReplicaOwnsTheLease() {
        TeamDeploymentPendingTimeoutService service = mock(TeamDeploymentPendingTimeoutService.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("team-deployment-pending-timeout", "pod-a", NOW, LEASE_DURATION))
                .thenReturn(true);
        when(service.reconcile(NOW, PENDING_TIMEOUT, 100))
                .thenReturn(new TeamDeploymentPendingTimeoutService.TimeoutResult(4, 2));

        TeamDeploymentPendingTimeoutJob job = new TeamDeploymentPendingTimeoutJob(service,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC),
                "pod-a", LEASE_DURATION, PENDING_TIMEOUT, 100);

        assertThat(job.runOnce()).isEqualTo(new RunResult(true, 4, 2));
        verify(leases).release("team-deployment-pending-timeout", "pod-a", NOW);
        verify(service).reconcile(NOW, PENDING_TIMEOUT, 100);
    }

    @Test
    void ignoresTheScanWhileAnotherReplicaOwnsTheLease() {
        TeamDeploymentPendingTimeoutService service = mock(TeamDeploymentPendingTimeoutService.class);
        SchedulerLeaseService leases = mock(SchedulerLeaseService.class);
        when(leases.run(eq("team-deployment-pending-timeout"), eq("pod-a"), eq(NOW), eq(LEASE_DURATION),
                any(Supplier.class))).thenReturn(new SchedulerLeaseService.Result<>(false, null));

        TeamDeploymentPendingTimeoutJob job = new TeamDeploymentPendingTimeoutJob(service, leases,
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-a", LEASE_DURATION, PENDING_TIMEOUT, 100);

        assertThat(job.runOnce()).isEqualTo(new RunResult(false, 0, 0));
        verify(service, org.mockito.Mockito.never()).reconcile(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
