package io.agentteams.controlplane.webhook;

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

class WebhookDeliverySchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    @Test
    void leaderDeliversBatchAndReleasesLease() {
        WebhookDeliveryService delivery = mock(WebhookDeliveryService.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("webhook-delivery", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(true);
        when(delivery.deliverDue(NOW, 10)).thenReturn(new WebhookDeliveryService.DeliveryResult(2, 1, 1));

        WebhookDeliveryScheduler scheduler = new WebhookDeliveryScheduler(delivery,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1", Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new WebhookDeliveryScheduler.RunResult(true, 2, 1, 1));
        verify(leases).release("webhook-delivery", "cp-1", NOW);
    }

    @Test
    void nonLeaderDoesNotDeliver() {
        WebhookDeliveryService delivery = mock(WebhookDeliveryService.class);
        SchedulerLeaseRepository leases = mock(SchedulerLeaseRepository.class);
        when(leases.tryAcquire("webhook-delivery", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(false);

        WebhookDeliveryScheduler scheduler = new WebhookDeliveryScheduler(delivery,
                new SchedulerLeaseService(leases), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1", Duration.ofSeconds(30), 10);

        assertThat(scheduler.runOnce()).isEqualTo(new WebhookDeliveryScheduler.RunResult(false, 0, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(delivery);
    }
}
