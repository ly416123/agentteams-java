package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DashboardAlertSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-25T01:00:00Z");

    @Test
    void evaluatesKnownScopesOnlyWhenThisReplicaOwnsTheLease() {
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("dashboard-alert", "pod-a", NOW, Duration.ofSeconds(30))).thenReturn(true);
        DashboardAlertDeliveryService delivery = mock(DashboardAlertDeliveryService.class);
        DashboardAlertEventRepository events = mock(DashboardAlertEventRepository.class);
        when(events.findUsageScopes()).thenReturn(java.util.List.of(
                new DashboardAlertEventRepository.AlertScope("tenant-a", "project-a")));
        when(delivery.retryDue(NOW)).thenReturn(new DashboardAlertDeliveryService.DeliveryResult(0, 0, 0));
        when(delivery.deliver("tenant-a", "project-a", NOW.minus(Duration.ofHours(24)), NOW))
                .thenReturn(new DashboardAlertDeliveryService.DeliveryResult(1, 0, 0));

        DashboardAlertScheduler scheduler = new DashboardAlertScheduler(delivery, events,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "pod-a",
                Duration.ofSeconds(30), Duration.ofHours(24), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new DashboardAlertScheduler.RunResult(true, 1, 1, 0));
        verify(leases).release("dashboard-alert", "pod-a", NOW);
        verify(delivery).deliver("tenant-a", "project-a", NOW.minus(Duration.ofHours(24)), NOW);
    }

    @Test
    void anchorsRollingWindowToMinuteBoundaryForStableEventFingerprints() {
        Instant now = Instant.parse("2026-08-25T01:00:42.123Z");
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("dashboard-alert", "pod-a", now, Duration.ofSeconds(30))).thenReturn(true);
        DashboardAlertDeliveryService delivery = mock(DashboardAlertDeliveryService.class);
        DashboardAlertEventRepository events = mock(DashboardAlertEventRepository.class);
        when(events.findUsageScopes()).thenReturn(java.util.List.of(
                new DashboardAlertEventRepository.AlertScope("tenant-a", "project-a")));
        when(delivery.retryDue(now)).thenReturn(new DashboardAlertDeliveryService.DeliveryResult(0, 0, 0));
        when(delivery.deliver("tenant-a", "project-a",
                Instant.parse("2026-08-24T01:00:00Z"), Instant.parse("2026-08-25T01:00:00Z")))
                .thenReturn(new DashboardAlertDeliveryService.DeliveryResult(1, 0, 0));

        DashboardAlertScheduler scheduler = new DashboardAlertScheduler(delivery, events,
                new SchedulerLeaseService(leases), Clock.fixed(now, ZoneOffset.UTC), "pod-a",
                Duration.ofSeconds(30), Duration.ofHours(24), 10);

        scheduler.runOnce();

        verify(delivery).deliver("tenant-a", "project-a",
                Instant.parse("2026-08-24T01:00:00Z"), Instant.parse("2026-08-25T01:00:00Z"));
    }
}
