package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-28T09:00:00Z");
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID EXECUTION_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EVALUATION_RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "alice");

    @Test
    void evaluatesDeterministicAssertionsAndKeepsTaskSuccessSeparate() throws Exception {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", NOW);
        EvaluationRunRecord running = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.RUNNING, NOW, null, NOW);
        EvaluationRunRecord failed = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.FAILED, NOW, NOW, NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case-1", "Case 1",
                JSON.createObjectNode(), JSON.readTree("[{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"},{\"type\":\"forbidden_tool\",\"name\":\"shell\"}]"), NOW);
        EvidenceBundle bundle = bundle("SUCCEEDED", List.of(new EvidenceBundle.ToolCallSummary("shell", 1, "VISIBLE")));
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), EVALUATION_RUN_ID,
                BigDecimal.ZERO, JSON.createObjectNode(), EvaluationRunRecord.Status.FAILED, true, NOW);
        when(repository.createRun(CONTEXT, command())).thenReturn(queued);
        when(repository.findCase(CONTEXT, CASE_ID)).thenReturn(java.util.Optional.of(evaluationCase));
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(bundle);
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenReturn(running, failed);
        when(repository.saveReport(any(), any())).thenReturn(report);

        EvaluationService.EvaluationOutcome outcome = new EvaluationService(repository, assembler,
                Clock.fixed(NOW, ZoneOffset.UTC)).evaluate(CONTEXT, command(), false);

        assertThat(outcome.run().status()).isEqualTo(EvaluationRunRecord.Status.FAILED);
        assertThat(outcome.report().taskSuccess()).isTrue();
        assertThat(outcome.results()).extracting(EvaluationResultRecord::status)
                .containsExactly(EvaluationResultRecord.Status.PASSED, EvaluationResultRecord.Status.FAILED);
        verify(repository).saveResults(CONTEXT, EVALUATION_RUN_ID, outcome.results());
    }

    @Test
    void returnsInconclusiveWhenSemanticEvaluationWasRequestedWithoutControlledEvaluator() throws Exception {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", NOW);
        EvaluationRunRecord running = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.RUNNING, NOW, null, NOW);
        EvaluationRunRecord inconclusive = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.INCONCLUSIVE, NOW, NOW, NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case-1", "Case 1",
                JSON.createObjectNode(), JSON.createArrayNode(), NOW);
        when(repository.createRun(CONTEXT, command())).thenReturn(queued);
        when(repository.findCase(CONTEXT, CASE_ID)).thenReturn(java.util.Optional.of(evaluationCase));
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(bundle("SUCCEEDED", List.of()));
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenReturn(running, inconclusive);
        when(repository.saveReport(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        EvaluationService.EvaluationOutcome outcome = new EvaluationService(repository, assembler,
                Clock.fixed(NOW, ZoneOffset.UTC)).evaluate(CONTEXT, command(), true);

        assertThat(outcome.run().status()).isEqualTo(EvaluationRunRecord.Status.INCONCLUSIVE);
        assertThat(outcome.report().aggregateScore()).isNull();
        assertThat(outcome.results()).singleElement().extracting(EvaluationResultRecord::status)
                .isEqualTo(EvaluationResultRecord.Status.INCONCLUSIVE);
    }

    @Test
    void passesWithACompleteResultManifestWithoutChangingTaskSuccess() throws Exception {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", NOW);
        EvaluationRunRecord running = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.RUNNING, NOW, null, NOW);
        EvaluationRunRecord passed = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.PASSED, NOW, NOW, NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case-1", "Case 1",
                JSON.createObjectNode(), JSON.readTree("[{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"},{\"type\":\"artifact_integrity\"}]"), NOW);
        EvidenceBundle bundle = bundle("SUCCEEDED", List.of());
        String storageRef = "tasks/00000000-0000-0000-0000-000000000003/attempts/00000000-0000-0000-0000-000000000099/artifacts/result.json";
        EvidenceBundle.ArtifactRef artifact = new EvidenceBundle.ArtifactRef("result.json", storageRef,
                "application/json", 12, "a".repeat(64));
        TaskResultManifest manifest = new TaskResultManifest(TASK_ID, EXECUTION_RUN_ID, "SUCCEEDED", "done",
                List.of(new TaskResultManifest.ArtifactMetadata("result.json", storageRef, "application/json",
                        12, "a".repeat(64), 1, "FINAL", TaskEventVisibility.REQUESTER)));
        bundle = new EvidenceBundle(bundle.taskId(), bundle.executionRunId(), bundle.taskSnapshot(), bundle.runSnapshot(),
                bundle.evidenceCompleteness(), bundle.taskEvents(), bundle.taskTree(), bundle.decisions(),
                bundle.toolCallSummaries(), manifest, List.of(artifact), bundle.approvalHistory(), bundle.agentMetadata(),
                bundle.policyOutcomes());
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), EVALUATION_RUN_ID,
                BigDecimal.ONE, JSON.createObjectNode(), EvaluationRunRecord.Status.PASSED, true, NOW);
        when(repository.createRun(CONTEXT, command())).thenReturn(queued);
        when(repository.findCase(CONTEXT, CASE_ID)).thenReturn(java.util.Optional.of(evaluationCase));
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(bundle);
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenReturn(running, passed);
        when(repository.saveReport(any(), any())).thenReturn(report);

        EvaluationService.EvaluationOutcome outcome = new EvaluationService(repository, assembler,
                Clock.fixed(NOW, ZoneOffset.UTC)).evaluate(CONTEXT, command(), false);

        assertThat(outcome.run().status()).isEqualTo(EvaluationRunRecord.Status.PASSED);
        assertThat(outcome.report().taskSuccess()).isTrue();
        assertThat(bundle.taskSnapshot().phase()).isEqualTo("SUCCEEDED");
        assertThat(outcome.results()).extracting(EvaluationResultRecord::status)
                .containsOnly(EvaluationResultRecord.Status.PASSED);
    }

    @Test
    void repeatsTheSameEvaluationIdentityForTheSameEvaluatorVersion() throws Exception {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", NOW);
        EvaluationRunRecord running = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.RUNNING, NOW, null, NOW);
        EvaluationRunRecord passed = new EvaluationRunRecord(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", EvaluationRunRecord.Status.PASSED, NOW, NOW, NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case-1", "Case 1",
                JSON.createObjectNode(), JSON.readTree("[{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"}]"), NOW);
        when(repository.createRun(CONTEXT, command())).thenReturn(queued);
        when(repository.findCase(CONTEXT, CASE_ID)).thenReturn(java.util.Optional.of(evaluationCase));
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(bundle("SUCCEEDED", List.of()));
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenReturn(running, passed, running, passed);
        when(repository.saveReport(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        EvaluationService service = new EvaluationService(repository, assembler, Clock.fixed(NOW, ZoneOffset.UTC));
        EvaluationService.EvaluationOutcome first = service.evaluate(CONTEXT, command(), false);
        EvaluationService.EvaluationOutcome second = service.evaluate(CONTEXT, command(), false);

        assertThat(second.run().id()).isEqualTo(first.run().id());
        assertThat(second.run().evaluatorVersion()).isEqualTo(first.run().evaluatorVersion());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2)).createRun(CONTEXT, command());
    }

    private static EvaluationRepository.CreateEvaluationRun command() {
        return new EvaluationRepository.CreateEvaluationRun(EVALUATION_RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                EXECUTION_RUN_ID, "evaluator-1", NOW);
    }

    private static EvidenceBundle bundle(String phase, List<EvidenceBundle.ToolCallSummary> tools) {
        return new EvidenceBundle(TASK_ID, EXECUTION_RUN_ID,
                new EvidenceBundle.TaskSnapshot(TASK_ID, phase, "chat", null),
                new EvidenceBundle.RunSnapshot("SUCCEEDED", NOW, NOW),
                new EvidenceBundle.EvidenceCompleteness("COMPLETE", 0, 1000), List.of(), List.of(), List.of(),
                tools, new TaskResultManifest(TASK_ID, EXECUTION_RUN_ID, "SUCCEEDED", "done", List.of()), List.of(),
                List.of(), new EvidenceBundle.AgentMetadata("runtime", "model", "1"), List.of());
    }
}
