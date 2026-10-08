package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.TaskTreeNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationServiceDeterministicAssertionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final UUID SUITE = id(1), CASE = id(2), TASK = id(3), EXECUTION = id(4), EVAL = id(5);
    private static final ExecutionContext CONTEXT = new ExecutionContext("org", "tenant", "project", "team", "user");

    @Test
    void comparesTaskPhaseAndRunStatusIndependentlyAndKeepsTaskSuccessSeparate() throws Exception {
        Outcome outcome = evaluate("[{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"},"
                + "{\"type\":\"run_status\",\"expected\":\"FAILED\"}]",
                bundle("SUCCEEDED", "FAILED", complete(), List.of(), List.of(), List.of(), null));

        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED,
                EvaluationResultRecord.Status.PASSED);
        assertThat(outcome.report().taskSuccess()).isTrue();
        assertThat(outcome.report().executionGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.PASSED);
        assertThat(outcome.report().resultGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.INCONCLUSIVE);
    }

    @Test
    void requiredToolNeedsSuccessfulCompletionAndFailureHasEvidence() throws Exception {
        Outcome succeeded = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"ok\":true,\"sequence\":2}")), List.of(), List.of(), null));
        assertThat(succeeded.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED);

        Outcome failed = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"ok\":false,\"errorCategory\":\"timeout\",\"sequence\":2}")), List.of(), List.of(), null));
        assertThat(failed.statuses()).containsExactly(EvaluationResultRecord.Status.FAILED);
        assertThat(failed.results().getFirst().evidenceRefs()).contains("task-events:" + EXECUTION);
        assertThat(failed.report().taskSuccess()).isTrue();
        assertThat(failed.report().executionGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.FAILED);
        assertThat(failed.report().resultGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.INCONCLUSIVE);

        Outcome missingCompletion = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}")), List.of(), List.of(), null));
        assertThat(missingCompletion.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome unknownCompletion = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":2}")), List.of(), List.of(), null));
        assertThat(unknownCompletion.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome partialOutcomes = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-2\",\"sequence\":3}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(partialOutcomes.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome extraCompletion = evaluate("[{\"type\":\"required_tool\",\"name\":\"web_search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-1\",\"ok\":true,\"sequence\":2}"),
                        event("tool.finished", "{\"tool\":\"web_search\",\"callId\":\"search-extra\",\"ok\":true,\"sequence\":3}")),
                        List.of(), List.of(), null));
        assertThat(extraCompletion.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);
        assertThat(extraCompletion.results().getFirst().findingSummary()).contains("unmatched");
    }

    @Test
    void requiredToolMatchesLifecycleByCallIdAndRejectsAmbiguousIds() throws Exception {
        Outcome matched = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-a\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(matched.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED);

        Outcome unknownId = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-unknown\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(unknownId.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome duplicateId = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":1}"),
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":2}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-a\",\"ok\":true,\"sequence\":3}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-a\",\"ok\":true,\"sequence\":4}")),
                        List.of(), List.of(), null));
        assertThat(duplicateId.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome duplicateAcrossTools = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"shared-id\",\"sequence\":1}"),
                        event("tool.called", "{\"tool\":\"fetch\",\"callId\":\"shared-id\",\"sequence\":2}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"shared-id\",\"ok\":true,\"sequence\":3}"),
                        event("tool.finished", "{\"tool\":\"fetch\",\"callId\":\"shared-id\",\"ok\":true,\"sequence\":4}")),
                        List.of(), List.of(), null));
        assertThat(duplicateAcrossTools.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome missingId = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(missingId.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome outOfOrder = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":2}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-a\",\"ok\":true,\"sequence\":1}")),
                        List.of(), List.of(), null));
        assertThat(outOfOrder.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        Outcome extraCompletion = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"call-a\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-a\",\"ok\":true,\"sequence\":2}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"call-extra\",\"ok\":true,\"sequence\":3}")),
                        List.of(), List.of(), null));
        assertThat(extraCompletion.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);
    }

    @Test
    void unavailableCalledPayloadMakesEveryToolAssertionInconclusive() throws Exception {
        EvidenceBundle evidence = bundle("SUCCEEDED", "SUCCEEDED", complete(),
                List.of(eventRef("tool.called", 1)), List.of(), List.of(), null);
        Outcome outcome = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"},"
                + "{\"type\":\"tool_order\",\"names\":[\"search\"]},"
                + "{\"type\":\"tool_count\",\"name\":\"search\",\"expected\":1},"
                + "{\"type\":\"failed_tool\",\"name\":\"search\"},"
                + "{\"type\":\"forbidden_tool\",\"name\":\"search\"}]", evidence);
        assertThat(outcome.statuses()).containsOnly(EvaluationResultRecord.Status.INCONCLUSIVE);
    }

    @Test
    void failedToolAssertionFailsWhenFailureOutcomeIsRecorded() throws Exception {
        Outcome outcome = evaluate("[{\"type\":\"failed_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"search-1\",\"ok\":false,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.FAILED);

        Outcome partialOutcomes = evaluate("[{\"type\":\"failed_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-2\",\"sequence\":3}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"search-1\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(partialOutcomes.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        EvidenceBundle.EvidenceCompleteness truncated = new EvidenceBundle.EvidenceCompleteness("TRUNCATED", 1001, 1000);
        Outcome visibleFailure = evaluate("[{\"type\":\"failed_tool\",\"name\":\"search\"}]",
                bundle("RUNNING", "RUNNING", truncated, List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"search-1\",\"ok\":false,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(visibleFailure.statuses()).containsExactly(EvaluationResultRecord.Status.FAILED);

        Outcome visibleFailureWithUnrelatedIncompleteCall = evaluate(
                "[{\"type\":\"failed_tool\",\"name\":\"search\"}]",
                bundle("RUNNING", "RUNNING", truncated, List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"search-1\",\"ok\":false,\"sequence\":2}"),
                        event("tool.called", "{\"tool\":\"other\",\"callId\":\"other-1\",\"sequence\":3}")),
                        List.of(), List.of(), null));
        assertThat(visibleFailureWithUnrelatedIncompleteCall.statuses())
                .containsExactly(EvaluationResultRecord.Status.FAILED);

        Outcome noVisibleFailure = evaluate("[{\"type\":\"failed_tool\",\"name\":\"search\"}]",
                bundle("RUNNING", "RUNNING", truncated,
                        List.of(event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}")),
                        List.of(), List.of(), null));
        assertThat(noVisibleFailure.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);
    }

    @Test
    void invalidPdfMetadataAndFailedDependencyAreDefiniteFailures() throws Exception {
        EvidenceBundle.ArtifactRef invalidPdf = new EvidenceBundle.ArtifactRef("report.pdf", "artifact:pdf",
                "text/plain", 0, "bad-sha");
        TaskResultManifest manifest = new TaskResultManifest(TASK, EXECUTION, "SUCCEEDED", "done", List.of());
        TaskTreeNode dependency = new TaskTreeNode(id(6), TASK, 1, "FAILED", List.of(), NOW);
        TaskTreeNode dependent = new TaskTreeNode(id(7), TASK, 2, "SUCCEEDED", List.of(id(6)), NOW);
        Outcome outcome = evaluate("[{\"type\":\"artifact_pdf\"},{\"type\":\"dependency_success\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(), List.of(dependency, dependent), List.of(invalidPdf), manifest));
        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.FAILED,
                EvaluationResultRecord.Status.FAILED);
    }

    @Test
    void checksToolOrderAndCountsFromSuccessfulLifecycleEvents() throws Exception {
        List<TaskProcessEvent> events = List.of(event("tool.called", "{\"tool\":\"search\",\"sequence\":1}"),
                event("tool.finished", "{\"tool\":\"search\",\"ok\":true,\"sequence\":2}"),
                event("tool.called", "{\"tool\":\"fetch\",\"sequence\":3}"),
                event("tool.finished", "{\"tool\":\"fetch\",\"ok\":true,\"sequence\":4}"));
        Outcome outcome = evaluate("[{\"type\":\"tool_order\",\"names\":[\"search\",\"fetch\"]},"
                + "{\"type\":\"tool_count\",\"name\":\"search\",\"expected\":1}]",
                bundle("RUNNING", "SUCCEEDED", complete(), events, List.of(), List.of(), null));
        assertThat(outcome.statuses()).containsOnly(EvaluationResultRecord.Status.PASSED);
    }

    @Test
    void checksDependencySuccessButDoesNotInventHandoffOrRetryEvidence() throws Exception {
        TaskTreeNode parent = new TaskTreeNode(TASK, null, 0, "SUCCEEDED", List.of(), NOW);
        TaskTreeNode dependency = new TaskTreeNode(id(6), TASK, 1, "SUCCEEDED", List.of(), NOW);
        TaskTreeNode dependent = new TaskTreeNode(id(7), TASK, 2, "SUCCEEDED", List.of(id(6)), NOW);
        Outcome outcome = evaluate("[{\"type\":\"dependency_success\"},{\"type\":\"dependency_handoff\"},"
                + "{\"type\":\"retry_limit\",\"max\":2}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(), List.of(parent, dependency, dependent), List.of(), null));
        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED,
                EvaluationResultRecord.Status.INCONCLUSIVE, EvaluationResultRecord.Status.INCONCLUSIVE);
        assertThat(outcome.results().get(1).evidenceRefs()).contains("task-tree:" + EXECUTION);
    }

    @Test
    void checksArtifactMetadataAndLeavesPdfSignatureInconclusiveWithoutBytes() throws Exception {
        EvidenceBundle.ArtifactRef pdf = new EvidenceBundle.ArtifactRef("report.pdf", "artifact:pdf", "application/pdf",
                128, "a".repeat(64));
        TaskResultManifest manifest = new TaskResultManifest(TASK, EXECUTION, "SUCCEEDED", "done", List.of());
        Outcome outcome = evaluate("[{\"type\":\"artifact_integrity\"},{\"type\":\"artifact_pdf\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(), List.of(), List.of(pdf), manifest));
        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED,
                EvaluationResultRecord.Status.INCONCLUSIVE);
        assertThat(outcome.results().get(1).findingSummary()).contains("signature");
    }

    @Test
    void incompleteEvidenceMakesEvidenceDependentAssertionInconclusive() throws Exception {
        Outcome outcome = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"},"
                + "{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", new EvidenceBundle.EvidenceCompleteness("TRUNCATED", 1001, 1000),
                        List.of(), List.of(), List.of(), null));
        assertThat(outcome.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE,
                EvaluationResultRecord.Status.PASSED);
        assertThat(outcome.report().executionGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.INCONCLUSIVE);
    }

    @Test
    void nonEventEvidenceGapsDoNotInvalidateCompleteToolEventsOrTaskTreeAssertions() throws Exception {
        EvidenceBundle.EvidenceCompleteness missingRunOrManifest =
                new EvidenceBundle.EvidenceCompleteness("INCONCLUSIVE", 4, 1000);
        Outcome toolOutcome = evaluate("[{\"type\":\"required_tool\",\"name\":\"search\"}]",
                bundle("SUCCEEDED", "UNKNOWN", missingRunOrManifest, List.of(
                        event("tool.called", "{\"tool\":\"search\",\"callId\":\"search-1\",\"sequence\":1}"),
                        event("tool.finished", "{\"tool\":\"search\",\"callId\":\"search-1\",\"ok\":true,\"sequence\":2}")),
                        List.of(), List.of(), null));
        assertThat(toolOutcome.statuses()).containsExactly(EvaluationResultRecord.Status.PASSED);

        TaskTreeNode failedDependency = new TaskTreeNode(id(6), TASK, 1, "FAILED", List.of(), NOW);
        TaskTreeNode dependent = new TaskTreeNode(id(7), TASK, 2, "SUCCEEDED", List.of(id(6)), NOW);
        Outcome dependencyFailure = evaluate("[{\"type\":\"dependency_success\"}]",
                bundle("SUCCEEDED", "UNKNOWN", missingRunOrManifest, List.of(),
                        List.of(failedDependency, dependent), List.of(), null));
        assertThat(dependencyFailure.statuses()).containsExactly(EvaluationResultRecord.Status.FAILED);

        Outcome emptyTree = evaluate("[{\"type\":\"dependency_success\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(), List.of(), List.of(), null));
        assertThat(emptyTree.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);

        TaskTreeNode missingDependency = new TaskTreeNode(id(7), TASK, 2, "SUCCEEDED", List.of(id(6)), NOW);
        Outcome missingNode = evaluate("[{\"type\":\"dependency_success\"}]",
                bundle("SUCCEEDED", "SUCCEEDED", complete(), List.of(), List.of(missingDependency), List.of(), null));
        assertThat(missingNode.statuses()).containsExactly(EvaluationResultRecord.Status.INCONCLUSIVE);
    }

    private static Outcome evaluate(String assertions, EvidenceBundle evidence) throws Exception {
        EvaluationRepository repository = mock(EvaluationRepository.class);
        EvidenceBundleAssembler assembler = mock(EvidenceBundleAssembler.class);
        EvaluationRunRecord queued = EvaluationRunRecord.queued(EVAL, SUITE, CASE, TASK, EXECUTION, "v1", NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE, SUITE, "case", "Case", JSON.createObjectNode(),
                JSON.readTree(assertions), NOW);
        when(repository.createRun(CONTEXT, command())).thenReturn(queued);
        when(repository.findCase(CONTEXT, CASE)).thenReturn(Optional.of(evaluationCase));
        when(assembler.assemble(any(), any(), any(), any())).thenReturn(evidence);
        when(repository.updateRunStatus(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                new EvaluationRunRecord(EVAL, SUITE, CASE, TASK, EXECUTION, "v1", invocation.getArgument(2), NOW,
                        invocation.getArgument(2) == EvaluationRunRecord.Status.RUNNING ? null : NOW, NOW));
        when(repository.saveReport(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        EvaluationService.EvaluationOutcome outcome = new EvaluationService(repository, assembler,
                Clock.fixed(NOW, ZoneOffset.UTC)).evaluate(CONTEXT, command(), false);
        return new Outcome(outcome.results(), outcome.report());
    }

    private static EvaluationRepository.CreateEvaluationRun command() {
        return new EvaluationRepository.CreateEvaluationRun(EVAL, SUITE, CASE, TASK, EXECUTION, "v1", NOW);
    }

    private static EvidenceBundle bundle(String phase, String runStatus, EvidenceBundle.EvidenceCompleteness completeness,
            List<TaskProcessEvent> events, List<TaskTreeNode> tree, List<EvidenceBundle.ArtifactRef> artifacts,
            TaskResultManifest manifest) {
        return new EvidenceBundle(TASK, EXECUTION, new EvidenceBundle.TaskSnapshot(TASK, phase, "agentscope", null),
                new EvidenceBundle.RunSnapshot(runStatus, NOW, NOW), completeness, events, tree, List.of(), List.of(),
                manifest, artifacts, List.of(), new EvidenceBundle.AgentMetadata("runtime", "model", "1"), List.of());
    }

    private static EvidenceBundle.EvidenceCompleteness complete() {
        return new EvidenceBundle.EvidenceCompleteness("COMPLETE", 2, 1000);
    }

    private static TaskProcessEvent event(String type, String payload) {
        long sequence;
        try { sequence = JSON.readTree(payload).path("sequence").asLong(); } catch (Exception ignored) { sequence = 0; }
        return new TaskProcessEvent(UUID.randomUUID(), TASK, EXECUTION, sequence, type, TaskEventVisibility.REQUESTER,
                NOW, "correlation", payload, null);
    }

    private static TaskProcessEvent eventRef(String type, long sequence) {
        return new TaskProcessEvent(UUID.randomUUID(), TASK, EXECUTION, sequence, type,
                TaskEventVisibility.REQUESTER, NOW, "correlation", null, "task-process:event-" + sequence);
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private record Outcome(List<EvaluationResultRecord> results, EvaluationReportRecord report) {
        List<EvaluationResultRecord.Status> statuses() { return results.stream().map(EvaluationResultRecord::status).toList(); }
    }
}
