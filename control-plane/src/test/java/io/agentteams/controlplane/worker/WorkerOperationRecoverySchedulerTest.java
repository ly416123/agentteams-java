package io.agentteams.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WorkerOperationRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void recoversExpiredOperationsOnlyWhileHoldingTheWorkerOperationLease() {
        SchedulerLeaseRepository repository = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        FoundationPersistenceService persistence = org.mockito.Mockito.mock(FoundationPersistenceService.class);
        when(repository.tryAcquire("worker-operation-recovery", "cp-1", NOW, Duration.ofSeconds(30)))
                .thenReturn(true);
        when(persistence.inTransaction(any())).thenReturn(3);

        WorkerOperationRecoveryScheduler scheduler = new WorkerOperationRecoveryScheduler(persistence,
                new SchedulerLeaseService(repository), Clock.fixed(NOW, ZoneOffset.UTC), "cp-1",
                Duration.ofSeconds(30));

        assertThat(scheduler.runOnce()).isEqualTo(new WorkerOperationRecoveryScheduler.RunResult(3));
        verify(persistence).inTransaction(any());
        verify(repository).release("worker-operation-recovery", "cp-1", NOW);
    }

    @Test
    void skipsRecoveryWhenAnotherReplicaOwnsTheWorkerOperationLease() {
        SchedulerLeaseRepository repository = org.mockito.Mockito.mock(SchedulerLeaseRepository.class);
        FoundationPersistenceService persistence = org.mockito.Mockito.mock(FoundationPersistenceService.class);
        when(repository.tryAcquire("worker-operation-recovery", "cp-2", NOW, Duration.ofSeconds(30)))
                .thenReturn(false);

        WorkerOperationRecoveryScheduler scheduler = new WorkerOperationRecoveryScheduler(persistence,
                new SchedulerLeaseService(repository), Clock.fixed(NOW, ZoneOffset.UTC), "cp-2",
                Duration.ofSeconds(30));

        assertThat(scheduler.runOnce()).isEqualTo(new WorkerOperationRecoveryScheduler.RunResult(0));
        org.mockito.Mockito.verifyNoInteractions(persistence);
    }
}
