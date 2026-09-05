package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.storage.ObjectStorage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConfigUploadServiceTest {
    @Test
    void cleanupDeletesOnlyExpiredPendingUploadAndMarksItDeleted() {
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        ConfigLifecycleRepository lifecycle = mock(ConfigLifecycleRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ConfigUploadRecord upload = new ConfigUploadRecord(UUID.randomUUID(), UUID.randomUUID(), "model.json",
                "configs/snapshot/files/model.json", "application/json", "sha", 3, "PENDING",
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, null);
        when(tx.configLifecycle()).thenReturn(lifecycle);
        when(lifecycle.findExpiredUploads(any(), any(Integer.class))).thenReturn(List.of(upload));
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            Function<FoundationTransaction, ?> work = invocation.getArgument(0);
            return work.apply(tx);
        });

        ConfigUploadService service = new ConfigUploadService(persistence, mock(ConfigSnapshotRepository.class),
                storage, mock(ArtifactService.class), Clock.fixed(Instant.ofEpochSecond(2), ZoneOffset.UTC));

        assertThat(service.cleanupExpired(10)).isEqualTo(1);
        verify(storage).delete(upload.storageKey());
        verify(lifecycle).markUploadDeleted(upload.id(), Instant.ofEpochSecond(2));
    }

    @Test
    void downloadsACompletedFileBySnapshotAndPathWithoutAcceptingAStorageKey() {
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        ConfigLifecycleRepository lifecycle = mock(ConfigLifecycleRepository.class);
        ConfigSnapshotRepository snapshots = mock(ConfigSnapshotRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        UUID snapshotId = UUID.randomUUID();
        ConfigFileRecord file = new ConfigFileRecord(UUID.randomUUID(), snapshotId, "models/default.json",
                "configs/" + snapshotId + "/files/models/default.json", "sha", 3, "application/json");
        java.io.ByteArrayInputStream content = new java.io.ByteArrayInputStream(new byte[] {1, 2, 3});
        when(tx.configLifecycle()).thenReturn(lifecycle);
        when(lifecycle.findFile(snapshotId, file.path())).thenReturn(Optional.of(file));
        when(storage.download(file.storageKey())).thenReturn(content);
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            Function<FoundationTransaction, ?> work = invocation.getArgument(0);
            return work.apply(tx);
        });

        ConfigUploadService service = new ConfigUploadService(persistence, snapshots, storage,
                mock(ArtifactService.class), Clock.systemUTC());

        assertThat(service.downloadCompleted(snapshotId, file.path()).content()).isSameAs(content);
        verify(storage).download(file.storageKey());
    }
}
