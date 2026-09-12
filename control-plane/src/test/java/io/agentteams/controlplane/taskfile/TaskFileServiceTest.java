package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TaskFileServiceTest {
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    private final ObjectStorage storage = mock(ObjectStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
    private final JdbcTaskFileRepository repository = mock(JdbcTaskFileRepository.class);
    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private final TaskRecord taskRecord = mock(TaskRecord.class);
    private TaskFileService service;

    @BeforeEach
    void setUp() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(persistence.findTask(TASK)).thenReturn(Optional.of(taskRecord));
        service = new TaskFileService(storageProvider, repository, persistence,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void uploadStoresComputesShaAndPersistsWithLatestAttempt() throws Exception {
        when(persistence.findTaskExecution(TASK)).thenReturn(List.of(execution(ATTEMPT, NOW.minusSeconds(60))));
        byte[] content = "pdf-bytes".getBytes();
        when(repository.findDedup(TASK, TaskFileRecord.OUTPUT, "报告.pdf", sha256Of(content)))
                .thenReturn(Optional.empty());
        when(repository.insert(any(TaskFileRecord.class))).thenReturn(true);

        TaskFileRecord uploaded = service.upload(TASK, "报告.pdf", "application/pdf", content);

        assertThat(uploaded.attemptId()).isEqualTo(ATTEMPT);
        assertThat(uploaded.sha256()).isEqualTo(sha256Of(content));
        assertThat(uploaded.storageKey()).startsWith("tasks/" + TASK + "/files/");
        assertThat(uploaded.storageKey()).endsWith("/报告.pdf");
        verify(storage).upload(eq(uploaded.storageKey()), any(InputStream.class), eq(9L), eq("application/pdf"));
        verify(repository).insert(uploaded);
    }

    @Test
    void uploadDedupReturnsExistingRecordWithoutNewObject() throws Exception {
        byte[] content = "same".getBytes();
        TaskFileRecord existing = record(TaskFileRecord.OUTPUT, "a.pdf", sha256Of(content));
        when(repository.findDedup(TASK, TaskFileRecord.OUTPUT, "a.pdf", sha256Of(content)))
                .thenReturn(Optional.of(existing));

        TaskFileRecord uploaded = service.upload(TASK, "a.pdf", "application/pdf", content);

        assertThat(uploaded).isSameAs(existing);
        verify(storage, never()).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(repository, never()).insert(any(TaskFileRecord.class));
    }

    @Test
    void uploadRejectsOversizeAndMissingTask() {
        byte[] big = new byte[(int) TaskFileService.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> service.upload(TASK, "big.bin", "application/octet-stream", big))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50MB");
        assertThatThrownBy(() -> service.registerInput(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "x.pdf", 3))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void registerInputSnapshotsMetadataAndDedupsBySource() {
        when(repository.findByTask(TASK, TaskFileRecord.INPUT)).thenReturn(List.of());
        when(repository.insert(any(TaskFileRecord.class))).thenReturn(true);

        TaskFileRecord registered = service.registerInput(TASK, UUID.randomUUID(), UUID.randomUUID(),
                "输入.pdf", 3);

        assertThat(registered.role()).isEqualTo(TaskFileRecord.INPUT);
        assertThat(registered.storageKey()).isEmpty();
        assertThat(registered.attemptId()).isNull();
        verify(repository).insert(registered);
    }

    @Test
    void reconcileMarksMissingObjectsOnly() {
        TaskFileRecord ok = record(TaskFileRecord.OUTPUT, "ok.pdf", "aa");
        TaskFileRecord gone = record(TaskFileRecord.OUTPUT, "gone.pdf", "bb");
        when(repository.findByTask(TASK, null)).thenReturn(List.of(ok, gone));
        when(storage.exists(ok.storageKey())).thenReturn(true);
        when(storage.exists(gone.storageKey())).thenReturn(false);

        service.reconcile(TASK);

        verify(repository).markMissing(gone.id(), NOW);
        verify(repository, never()).markMissing(eq(ok.id()), any());
    }

    @Test
    void reconcileBatchMarksMissingAcrossTasksAndCounts() {
        TaskFileRecord gone = record(TaskFileRecord.OUTPUT, "gone.pdf", "bb");
        when(repository.findAvailableOutputs(100)).thenReturn(List.of(gone));
        when(storage.exists(gone.storageKey())).thenReturn(false);
        when(repository.markMissing(gone.id(), NOW)).thenReturn(true);

        int marked = service.reconcileBatch(100);

        assertThat(marked).isEqualTo(1);
    }

    @Test
    void reconcileBatchIsNoopWithoutStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.reconcileBatch(100)).isZero();
        verify(repository, never()).findAvailableOutputs(anyInt());
    }

    private FoundationPersistenceService.TaskExecutionRecord execution(UUID attemptId, Instant createdAt) {
        TaskAttemptRecord attempt = new TaskAttemptRecord(attemptId, TASK, UUID.randomUUID(),
                io.agentteams.domain.task.TaskPhase.RUNNING, NOW.plusSeconds(300), null,
                "worker-1", "gRPC", null, null, createdAt, createdAt, 0);
        return new FoundationPersistenceService.TaskExecutionRecord(attempt, null, null);
    }

    private TaskFileRecord record(String role, String name, String sha) {
        return new TaskFileRecord(UUID.randomUUID(), TASK, ATTEMPT, role, name, "application/pdf",
                3, sha, role.equals(TaskFileRecord.OUTPUT) ? "tasks/" + TASK + "/files/f/" + name : "",
                UUID.randomUUID(), UUID.randomUUID(), TaskFileRecord.AVAILABLE, NOW, NOW);
    }

    private static String sha256Of(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }
}
