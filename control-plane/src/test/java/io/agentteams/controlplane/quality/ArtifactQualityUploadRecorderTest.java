package io.agentteams.controlplane.quality;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactQualityUploadRecorderTest {
    private final TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
    private final ArtifactQualityLedgerService ledger = mock(ArtifactQualityLedgerService.class);
    private final ArtifactQualityUploadRecorder recorder = new ArtifactQualityUploadRecorder(runs, ledger,
            Clock.fixed(Instant.parse("2026-10-15T01:02:04.123456Z"), ZoneOffset.UTC));

    @Test
    void recordsOnlyForLatestActiveRunAndUsesPersistedTimestampForSequence() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext("org", "tenant", projectId.toString(), "team", "worker");
        Instant createdAt = Instant.parse("2026-10-15T01:02:03.456789Z");
        TaskFileRecord file = output(taskId, createdAt);
        when(runs.contextForTask(taskId)).thenReturn(Optional.of(context));
        when(runs.latestRunId(taskId)).thenReturn(Optional.of(runId));
        when(runs.snapshotForRun(context, taskId, runId)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("RUNNING", "RUNNING", createdAt,
                        null, "agentscope", null)));

        recorder.recordForActiveRun(taskId, file);

        Instant observedAt = Instant.parse("2026-10-15T01:02:04.123456Z");
        long expectedMicros = observedAt.getEpochSecond() * 1_000_000L + 123_456L;
        verify(ledger).recordUploadedOutput(context, taskId, runId, file, expectedMicros, observedAt);
    }

    @Test
    void ignoresTerminalRunsAndNonOutputFiles() {
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext("org", "tenant", UUID.randomUUID().toString(), "team", "worker");
        Instant createdAt = Instant.parse("2026-10-15T01:02:03Z");
        when(runs.contextForTask(taskId)).thenReturn(Optional.of(context));
        when(runs.latestRunId(taskId)).thenReturn(Optional.of(runId));
        when(runs.snapshotForRun(context, taskId, runId)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("SUCCEEDED", "SUCCEEDED", createdAt,
                        createdAt, "agentscope", null)));

        recorder.recordForActiveRun(taskId, output(taskId, createdAt));
        recorder.recordForActiveRun(taskId, new TaskFileRecord(UUID.randomUUID(), taskId, null,
                TaskFileRecord.INPUT, "input.pdf", null, 1, null, "", UUID.randomUUID(), UUID.randomUUID(),
                TaskFileRecord.AVAILABLE, createdAt, createdAt));

        verify(ledger, never()).recordUploadedOutput(any(), any(), any(), any(), any(Long.class), any());
    }

    private static TaskFileRecord output(UUID taskId, Instant createdAt) {
        return new TaskFileRecord(UUID.randomUUID(), taskId, null, TaskFileRecord.OUTPUT, "report.pdf",
                "application/pdf", 5, "ab".repeat(32), "object-key", null, null,
                TaskFileRecord.AVAILABLE, createdAt, createdAt);
    }
}
