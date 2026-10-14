package io.agentteams.controlplane.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.agentteams.controlplane.evaluation.EvaluationResultRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class ArtifactQualityLedgerServiceTest {
    @Mock private ArtifactQualityEventRepository repository;

    @Test
    void persistsCheckAndFinalStatusFromTheSameEvaluationEvidence() {
        ArtifactQualityLedgerService service = new ArtifactQualityLedgerService(repository);
        UUID task = UUID.randomUUID();
        UUID executionRun = UUID.randomUUID();
        UUID evaluationRun = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext("org", "tenant", UUID.randomUUID().toString(), "team", "user");
        EvaluationResultRecord check = new EvaluationResultRecord(UUID.randomUUID(), evaluationRun,
                "artifact_pdf_quality", null, null, EvaluationResultRecord.Status.FAILED,
                List.of("result-manifest:" + executionRun), "exactly one PDF is required", Instant.parse("2026-10-13T01:00:00Z"));

        service.recordEvaluation(context, task, executionRun, evaluationRun, List.of(check), Instant.parse("2026-10-13T01:01:00Z"));

        ArgumentCaptor<ArtifactQualityEvent> captor = ArgumentCaptor.forClass(ArtifactQualityEvent.class);
        verify(repository, times(2)).append(any(), captor.capture());
        assertEquals(List.of("QUALITY_CHECK", "FINAL_STATUS"), captor.getAllValues().stream()
                .map(ArtifactQualityEvent::eventType).toList());
        assertEquals("FAILED", captor.getAllValues().get(0).status());
        assertEquals("FAILED", captor.getAllValues().get(1).status());
        assertEquals("LIVE", captor.getAllValues().get(0).sourceType());
        assertEquals(executionRun, captor.getAllValues().get(0).runId());
    }

    @Test
    void eventStoreFailureDoesNotChangeEvaluationCallerOutcome() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ArtifactQualityLedgerService service = new ArtifactQualityLedgerService(repository, meters);
        doThrow(new IllegalStateException("database unavailable")).when(repository).append(any(), any());

        service.recordEvaluation(new ExecutionContext("org", "tenant", UUID.randomUUID().toString(), "team", "user"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(), Instant.now());

        verify(repository).append(any(), any());
        assertEquals(1.0, meters.get("agentteams.artifact_quality.capture.events")
                .tag("event_type", "FINAL_STATUS").tag("result", "failed").counter().count());
    }

    @Test
    void recordsStartAndNoFileGenerationFailureAsSeparateFacts() {
        ArtifactQualityLedgerService service = new ArtifactQualityLedgerService(repository);
        ExecutionContext context = new ExecutionContext("org", "tenant", UUID.randomUUID().toString(), "team", "user");
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        service.recordGenerationStarted(context, taskId, runId, UUID.randomUUID(), 0, Instant.now());
        service.recordManifest(context, new io.agentteams.application.api.TaskResultManifest(taskId, runId,
                "FAILED", "this must not be recorded", List.of()), Instant.now());

        ArgumentCaptor<ArtifactQualityEvent> captor = ArgumentCaptor.forClass(ArtifactQualityEvent.class);
        verify(repository, times(2)).append(any(), captor.capture());
        assertEquals("GENERATE_STARTED", captor.getAllValues().get(0).eventType());
        assertEquals("GENERATE_FAILED", captor.getAllValues().get(1).eventType());
        assertEquals(null, captor.getAllValues().get(1).fileId());
        assertEquals("Artifact generation failed; detailed cause is unavailable", captor.getAllValues().get(1).summary());
    }
}
