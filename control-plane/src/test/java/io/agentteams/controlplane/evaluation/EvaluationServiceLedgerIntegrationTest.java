package io.agentteams.controlplane.evaluation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.quality.ArtifactQualityLedgerService;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EvaluationServiceLedgerIntegrationTest {
    @Test
    void appendsTheRealFailedArtifactCheckAfterEvaluationIsPersisted() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID suiteId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID executionRunId = UUID.randomUUID();
        UUID evaluationRunId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-13T01:00:00Z");
        ExecutionContext context = new ExecutionContext("org", "tenant", UUID.randomUUID().toString(), "team", "user");
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        ArtifactQualityLedgerService ledger = mock(ArtifactQualityLedgerService.class);
        EvaluationRepository.CreateEvaluationRun command = new EvaluationRepository.CreateEvaluationRun(
                evaluationRunId, suiteId, caseId, taskId, executionRunId, "deterministic-v1", now);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(evaluationRunId, suiteId, caseId, taskId,
                executionRunId, "deterministic-v1", now);
        EvaluationRunRecord running = new EvaluationRunRecord(evaluationRunId, suiteId, caseId, taskId,
                executionRunId, "deterministic-v1", EvaluationRunRecord.Status.RUNNING, now, null, now);
        EvaluationRunRecord failed = new EvaluationRunRecord(evaluationRunId, suiteId, caseId, taskId,
                executionRunId, "deterministic-v1", EvaluationRunRecord.Status.FAILED, now, now, now);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(caseId, suiteId, "pdf", "PDF",
                json.createObjectNode(), json.readTree("[{\"type\":\"artifact_pdf\"}]"), now);
        TaskResultManifest manifest = new TaskResultManifest(taskId, executionRunId, "SUCCEEDED", "complete", List.of());
        EvidenceBundle bundle = new EvidenceBundle(taskId, executionRunId,
                new EvidenceBundle.TaskSnapshot(taskId, "SUCCEEDED", "AGENTSCOPE", null),
                new EvidenceBundle.RunSnapshot("SUCCEEDED", now, now),
                new EvidenceBundle.EvidenceCompleteness("COMPLETE", 1, 1000), List.of(), List.of(), List.of(),
                List.of(), manifest, List.of(), List.of(), new EvidenceBundle.AgentMetadata("runtime", "model", "1"), List.of());
        when(repository.createRun(context, command)).thenReturn(queued);
        when(repository.findCase(context, caseId)).thenReturn(Optional.of(evaluationCase));
        when(assembler.assemble(any(), eq(taskId), eq(executionRunId), any())).thenReturn(bundle);
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenReturn(running, failed);
        when(repository.saveReport(any(), any())).thenAnswer(call -> call.getArgument(1));
        EvaluationService service = new EvaluationService(repository, assembler, ledger,
                Clock.fixed(now, ZoneOffset.UTC));

        EvaluationService.EvaluationOutcome outcome = service.evaluate(context, command, false);

        org.junit.jupiter.api.Assertions.assertEquals(EvaluationResultRecord.Status.FAILED, outcome.results().get(0).status());
        verify(ledger).recordEvaluation(context, taskId, executionRunId, evaluationRunId,
                outcome.results(), manifest, now);
    }
}
