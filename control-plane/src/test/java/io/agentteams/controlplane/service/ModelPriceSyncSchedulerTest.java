package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ModelPriceSyncSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Test
    void onlyRunsSyncWhenReplicaOwnsLease() {
        SchedulerLeaseRepository leases = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        ModelPriceSyncService service = org.mockito.Mockito.mock(ModelPriceSyncService.class);
        when(leases.tryAcquire("model-price-sync", "cp-1", NOW, Duration.ofSeconds(30))).thenReturn(false);

        ModelPriceSyncScheduler scheduler = new ModelPriceSyncScheduler(service, new SchedulerLeaseService(leases),
                Clock.fixed(NOW, ZoneOffset.UTC), "cp-1", Duration.ofSeconds(30));

        assertThat(scheduler.runOnce()).isEqualTo(new ModelPriceSyncScheduler.RunResult(false, 0, 0, 0, 0));
        verifyNoInteractions(service);
    }
}
