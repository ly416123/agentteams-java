package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.task.JdbcTaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskDecisionRecordRepository;
import io.agentteams.controlplane.task.TaskDecisionRecord;
import io.agentteams.controlplane.task.TaskProcessEventRepository;
import io.agentteams.controlplane.task.TaskResultManifestRepository;
import io.agentteams.controlplane.task.TaskRunObservationRepository;
import io.agentteams.controlplane.task.TaskTreeNode;
import io.agentteams.controlplane.task.TaskTreeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceBundleAssemblerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "alice");

    @Test
    void assemblesScopedEvidenceAndSummarizesToolCallsAndArtifacts() throws Exception {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventRepository events = mock(TaskProcessEventRepository.class);
        TaskTreeRepository tree = mock(TaskTreeRepository.class);
        TaskDecisionRecordRepository decisions = mock(TaskDecisionRecordRepository.class);
        TaskResultManifestRepository manifests = mock(TaskResultManifestRepository.class);
        TaskProcessEvent tool = event("tool.called", "{\"tool\":\"web_search\",\"result\":\"ok\"}");
        TaskResultManifest result = new TaskResultManifest(TASK_ID, RUN_ID, "SUCCEEDED", "ok", List.of(
                new TaskResultManifest.ArtifactMetadata("answer.md", "https://minio.invalid/answer?X-Amz-Signature=fixture", "text/markdown",
                        12, "a".repeat(64), 1, "FINAL", TaskEventVisibility.REQUESTER)));
        when(runs.snapshotForRun(CONTEXT, TASK_ID, RUN_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("RUNNING", "FAILED", NOW, NOW.plusSeconds(9),
                        "chat", "conversation-1")));
        when(events.find(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 1001))
                .thenReturn(List.of(tool));
        when(tree.find(CONTEXT, RUN_ID)).thenReturn(List.of(new TaskTreeNode(TASK_ID, null, 0, "SUCCEEDED", List.of(), NOW)));
        when(decisions.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(List.of());
        when(manifests.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER)))
                .thenReturn(Optional.of(result));

        EvidenceBundle bundle = assembler(runs, events, tree, decisions, manifests).assemble(CONTEXT, TASK_ID, RUN_ID,
                Set.of(TaskEventVisibility.REQUESTER));

        assertThat(bundle.inputStatus()).isEqualTo(EvidenceBundle.InputStatus.COMPLETE);
        assertThat(bundle.toolCallSummaries()).extracting(EvidenceBundle.ToolCallSummary::toolName)
                .containsExactly("web_search");
        assertThat(bundle.artifactRefs()).singleElement().satisfies(ref -> {
            assertThat(ref.storageRef()).startsWith("artifact:");
            assertThat(ref.storageRef()).doesNotContain("downloadUrl", "X-Amz", "?");
        });
        assertThat(bundle.taskSnapshot().taskType()).isEqualTo("chat");
        assertThat(bundle.taskSnapshot().phase()).isEqualTo("RUNNING");
        assertThat(bundle.runSnapshot()).isEqualTo(new EvidenceBundle.RunSnapshot("FAILED", NOW, NOW.plusSeconds(9)));
        assertThat(bundle.taskSnapshot().phase()).isNotEqualTo(bundle.resultManifest().status());
        assertThat(bundle.runSnapshot().status()).isNotEqualTo(bundle.resultManifest().status());
        assertThat(bundle.evidenceCompleteness()).isEqualTo(
                new EvidenceBundle.EvidenceCompleteness("COMPLETE", 1, 1000));
        verify(events).find(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 1001);
    }

    @Test
    void marksMissingResultManifestAsInconclusiveAndTrimsOversizedPayloadToReference() throws Exception {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventRepository events = mock(TaskProcessEventRepository.class);
        TaskTreeRepository tree = mock(TaskTreeRepository.class);
        TaskDecisionRecordRepository decisions = mock(TaskDecisionRecordRepository.class);
        TaskResultManifestRepository manifests = mock(TaskResultManifestRepository.class);
        TaskProcessEvent oversized = mock(TaskProcessEvent.class);
        when(oversized.eventId()).thenReturn(UUID.randomUUID());
        when(oversized.taskId()).thenReturn(TASK_ID);
        when(oversized.runId()).thenReturn(RUN_ID);
        when(oversized.sequence()).thenReturn(1L);
        when(oversized.eventType()).thenReturn("tool.called");
        when(oversized.visibility()).thenReturn(TaskEventVisibility.REQUESTER);
        when(oversized.occurredAt()).thenReturn(NOW);
        when(oversized.correlationId()).thenReturn("corr-1");
        when(oversized.payload()).thenReturn("x".repeat(16 * 1024 + 1) + "api_key=secret");
        when(oversized.payloadRef()).thenReturn("task-process:event-1");
        when(runs.snapshotForRun(CONTEXT, TASK_ID, RUN_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("FAILED", "RUNNING", NOW, null, "chat", null)));
        when(events.find(eq(CONTEXT), eq(TASK_ID), eq(RUN_ID), eq(0L), any(), eq(1001)))
                .thenReturn(List.of(oversized));
        when(tree.find(CONTEXT, RUN_ID)).thenReturn(List.of());
        when(decisions.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(List.of());
        when(manifests.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(Optional.empty());

        EvidenceBundle bundle = assembler(runs, events, tree, decisions, manifests).assemble(CONTEXT, TASK_ID, RUN_ID,
                Set.of(TaskEventVisibility.REQUESTER));

        assertThat(bundle.inputStatus()).isEqualTo(EvidenceBundle.InputStatus.INCONCLUSIVE);
        assertThat(bundle.taskSnapshot().phase()).isEqualTo("FAILED");
        assertThat(bundle.runSnapshot()).isEqualTo(new EvidenceBundle.RunSnapshot("RUNNING", NOW, null));
        assertThat(bundle.evidenceCompleteness().status()).isEqualTo("INCONCLUSIVE");
        assertThat(bundle.taskEvents()).singleElement().satisfies(event -> {
            assertThat(event.payload()).isNull();
            assertThat(event.payloadRef()).isEqualTo("task-process:event-1");
        });
    }

    @Test
    void missingRunRemainsUnknownWithoutReplacingManifestStatus() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventRepository events = mock(TaskProcessEventRepository.class);
        TaskTreeRepository tree = mock(TaskTreeRepository.class);
        TaskDecisionRecordRepository decisions = mock(TaskDecisionRecordRepository.class);
        TaskResultManifestRepository manifests = mock(TaskResultManifestRepository.class);
        TaskResultManifest manifest = new TaskResultManifest(TASK_ID, RUN_ID, "SUCCEEDED", "done", List.of());
        when(runs.snapshotForRun(CONTEXT, TASK_ID, RUN_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("RUNNING", null, NOW, null, "chat", null)));
        when(events.find(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 1001)).thenReturn(List.of());
        when(tree.find(CONTEXT, RUN_ID)).thenReturn(List.of());
        when(decisions.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(List.of());
        when(manifests.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(Optional.of(manifest));

        EvidenceBundle bundle = assembler(runs, events, tree, decisions, manifests).assemble(CONTEXT, TASK_ID, RUN_ID,
                Set.of(TaskEventVisibility.REQUESTER));

        assertThat(bundle.taskSnapshot().phase()).isEqualTo("RUNNING");
        assertThat(bundle.runSnapshot()).isEqualTo(new EvidenceBundle.RunSnapshot("UNKNOWN", null, null));
        assertThat(bundle.resultManifest().status()).isEqualTo("SUCCEEDED");
        assertThat(bundle.evidenceCompleteness().status()).isEqualTo("INCONCLUSIVE");
        assertThat(bundle.inputStatus()).isEqualTo(EvidenceBundle.InputStatus.INCONCLUSIVE);
    }

    @Test
    void requestsOneBeyondLimitAndRetainsOnlyOneThousandSanitizedEvents() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventRepository events = mock(TaskProcessEventRepository.class);
        TaskTreeRepository tree = mock(TaskTreeRepository.class);
        TaskDecisionRecordRepository decisions = mock(TaskDecisionRecordRepository.class);
        TaskResultManifestRepository manifests = mock(TaskResultManifestRepository.class);
        List<TaskProcessEvent> observed = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(EvidenceBundleAssemblerTest::oversizedEvent).toList();
        when(runs.snapshotForRun(CONTEXT, TASK_ID, RUN_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("RUNNING", "RUNNING", NOW, null, "chat", null)));
        when(events.find(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 1001)).thenReturn(observed);
        when(tree.find(CONTEXT, RUN_ID)).thenReturn(List.of());
        when(decisions.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(List.of());
        when(manifests.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(Optional.of(
                new TaskResultManifest(TASK_ID, RUN_ID, "SUCCEEDED", "done", List.of())));

        EvidenceBundle bundle = assembler(runs, events, tree, decisions, manifests).assemble(CONTEXT, TASK_ID, RUN_ID,
                Set.of(TaskEventVisibility.REQUESTER));

        assertThat(bundle.taskEvents()).hasSize(1000).allSatisfy(event -> assertThat(event.payload()).isNull());
        assertThat(bundle.evidenceCompleteness()).isEqualTo(
                new EvidenceBundle.EvidenceCompleteness("TRUNCATED", 1001, 1000));
        assertThat(bundle.inputStatus()).isEqualTo(EvidenceBundle.InputStatus.INCONCLUSIVE);
    }

    @Test
    void exactlyOneThousandEventsAreCompleteWhenRunAndManifestAreVisible() {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskProcessEventRepository events = mock(TaskProcessEventRepository.class);
        TaskTreeRepository tree = mock(TaskTreeRepository.class);
        TaskDecisionRecordRepository decisions = mock(TaskDecisionRecordRepository.class);
        TaskResultManifestRepository manifests = mock(TaskResultManifestRepository.class);
        List<TaskProcessEvent> observed = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> event("progress", "{\"step\":" + i + "}")).toList();
        when(runs.snapshotForRun(CONTEXT, TASK_ID, RUN_ID)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("SUCCEEDED", "SUCCEEDED", NOW, NOW, "chat", null)));
        when(events.find(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 1001)).thenReturn(observed);
        when(tree.find(CONTEXT, RUN_ID)).thenReturn(List.of());
        when(decisions.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(List.of());
        when(manifests.find(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER))).thenReturn(Optional.of(
                new TaskResultManifest(TASK_ID, RUN_ID, "READY", "done", List.of())));

        EvidenceBundle bundle = assembler(runs, events, tree, decisions, manifests).assemble(CONTEXT, TASK_ID, RUN_ID,
                Set.of(TaskEventVisibility.REQUESTER));

        assertThat(bundle.taskEvents()).hasSize(1000);
        assertThat(bundle.evidenceCompleteness()).isEqualTo(
                new EvidenceBundle.EvidenceCompleteness("COMPLETE", 1000, 1000));
    }

    private static EvidenceBundleAssembler assembler(TaskRunObservationRepository runs,
            TaskProcessEventRepository events, TaskTreeRepository tree, TaskDecisionRecordRepository decisions,
            TaskResultManifestRepository manifests) {
        return new EvidenceBundleAssembler(runs, events, tree, decisions, manifests);
    }

    private static TaskProcessEvent event(String type, String payload) {
        return new TaskProcessEvent(UUID.randomUUID(), TASK_ID, RUN_ID, 1, type, TaskEventVisibility.REQUESTER, NOW,
                "corr-1", payload, null);
    }

    private static TaskProcessEvent oversizedEvent(int sequence) {
        TaskProcessEvent event = mock(TaskProcessEvent.class);
        when(event.eventId()).thenReturn(UUID.randomUUID());
        when(event.taskId()).thenReturn(TASK_ID);
        when(event.runId()).thenReturn(RUN_ID);
        when(event.sequence()).thenReturn((long) sequence);
        when(event.eventType()).thenReturn("progress");
        when(event.visibility()).thenReturn(TaskEventVisibility.REQUESTER);
        when(event.occurredAt()).thenReturn(NOW);
        when(event.correlationId()).thenReturn("corr-1");
        when(event.payload()).thenReturn("x".repeat(16 * 1024 + 1));
        when(event.payloadRef()).thenReturn(null);
        return event;
    }
}
