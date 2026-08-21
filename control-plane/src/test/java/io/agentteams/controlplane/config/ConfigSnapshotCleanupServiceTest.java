package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.storage.ObjectStorage;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConfigSnapshotCleanupServiceTest {
    @Test
    void deletesObjectsBeforeDatabaseSnapshotAndKeepsFailedCandidateRetryable() {
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        ConfigLifecycleRepository lifecycle = mock(ConfigLifecycleRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID snapshotId = UUID.randomUUID();
        when(tx.configLifecycle()).thenReturn(lifecycle);
        when(lifecycle.findCleanupSnapshotIds(2, 10)).thenReturn(List.of(snapshotId));
        when(lifecycle.findObjectKeys(snapshotId)).thenReturn(List.of("configs/old/file.json"));
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            Function<FoundationTransaction, ?> work = invocation.getArgument(0);
            return work.apply(tx);
        });

        ConfigSnapshotCleanupService service = new ConfigSnapshotCleanupService(persistence, storage);

        assertThat(service.cleanup(2, 10)).isEqualTo(1);
        verify(storage).delete("configs/old/file.json");
        verify(lifecycle).deleteSnapshot(snapshotId);

        doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("configs/old/file.json");
        assertThat(service.cleanup(2, 10)).isZero();
        verify(lifecycle, times(1)).deleteSnapshot(eq(snapshotId));
    }
}
