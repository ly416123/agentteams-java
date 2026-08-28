package io.agentteams.controlplane.usage;

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

class UsageBudgetSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void evaluatesPoliciesOnlyWhenThisReplicaOwnsTheLease() {
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("usage-budget", "pod-a", NOW, Duration.ofSeconds(30))).thenReturn(true);
        UsageBudgetDeliveryService delivery = mock(UsageBudgetDeliveryService.class);
        when(delivery.runOnce(NOW, 10)).thenReturn(new UsageBudgetDeliveryService.RunResult(1, 2, 0));

        UsageBudgetScheduler scheduler = new UsageBudgetScheduler(delivery, new SchedulerLeaseService(leases),
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-a", Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new UsageBudgetScheduler.RunResult(true, 1, 2, 0));
        verify(leases).release("usage-budget", "pod-a", NOW);
        verify(delivery).runOnce(NOW, 10);
    }

    @Test
    void reportsNonLeaderWithoutRunningBudgetWork() {
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("usage-budget", "pod-b", NOW, Duration.ofSeconds(30))).thenReturn(false);
        UsageBudgetDeliveryService delivery = mock(UsageBudgetDeliveryService.class);

        UsageBudgetScheduler scheduler = new UsageBudgetScheduler(delivery, new SchedulerLeaseService(leases),
                Clock.fixed(NOW, ZoneOffset.UTC), "pod-b", Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new UsageBudgetScheduler.RunResult(false, 0, 0, 0));
    }
}
