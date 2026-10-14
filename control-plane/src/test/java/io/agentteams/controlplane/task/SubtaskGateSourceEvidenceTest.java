package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.domain.task.TaskPhase;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubtaskGateSourceEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ignoresSourceEvidenceWhenLatestChildRunDidNotSucceed() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext("org", "tenant", "project", "team", "subject");
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskSourceEvidenceRepository evidence = mock(TaskSourceEvidenceRepository.class);
        when(runs.contextForTask(parentId)).thenReturn(Optional.of(context));
        when(runs.contextForTask(childId)).thenReturn(Optional.of(context));
        when(runs.latestRunId(childId)).thenReturn(Optional.of(runId));
        when(runs.snapshotForRun(context, childId, runId)).thenReturn(Optional.of(
                new TaskRunObservationRepository.TaskRunSnapshot("SUCCEEDED", "FAILED", NOW, NOW, "agentscope", null)));
        SubtaskGateService service = new SubtaskGateService(mock(FoundationPersistenceService.class),
                Clock.systemUTC(), runs, evidence);

        Map<UUID, TaskSourceEvidence> result = loadEvidence(service, parentId, List.of(task(childId)));

        assertThat(result).isEmpty();
        verify(runs).snapshotForRun(context, childId, runId);
        verify(evidence, never()).find(any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void doesNotReadChildEvidenceOutsideParentResourceScope() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ExecutionContext parentContext = new ExecutionContext("org", "tenant-a", "project", "team", "subject");
        ExecutionContext childContext = new ExecutionContext("org", "tenant-b", "project", "team", "subject");
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskSourceEvidenceRepository evidence = mock(TaskSourceEvidenceRepository.class);
        when(runs.contextForTask(parentId)).thenReturn(Optional.of(parentContext));
        when(runs.contextForTask(childId)).thenReturn(Optional.of(childContext));
        when(runs.latestRunId(childId)).thenReturn(Optional.of(runId));
        SubtaskGateService service = new SubtaskGateService(mock(FoundationPersistenceService.class),
                Clock.systemUTC(), runs, evidence);

        Map<UUID, TaskSourceEvidence> result = loadEvidence(service, parentId, List.of(task(childId)));

        assertThat(result).isEmpty();
        verify(evidence, never()).find(any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void keepsSiblingEvidenceWhenOneChildEvidenceLookupFails() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID failedChildId = UUID.randomUUID();
        UUID validChildId = UUID.randomUUID();
        UUID failedRunId = UUID.randomUUID();
        UUID validRunId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext("org", "tenant", "project", "team", "subject");
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskSourceEvidenceRepository evidence = mock(TaskSourceEvidenceRepository.class);
        when(runs.contextForTask(parentId)).thenReturn(Optional.of(context));
        when(runs.contextForTask(failedChildId)).thenReturn(Optional.of(context));
        when(runs.contextForTask(validChildId)).thenReturn(Optional.of(context));
        when(runs.latestRunId(failedChildId)).thenReturn(Optional.of(failedRunId));
        when(runs.latestRunId(validChildId)).thenReturn(Optional.of(validRunId));
        when(runs.snapshotForRun(context, failedChildId, failedRunId)).thenReturn(Optional.of(succeededSnapshot()));
        when(runs.snapshotForRun(context, validChildId, validRunId)).thenReturn(Optional.of(succeededSnapshot()));
        when(evidence.find(context, failedChildId, failedRunId, 500, 10_000))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        TaskSourceEvidence validEvidence = new TaskSourceEvidence(validChildId, validRunId, List.of(
                new TaskSourceEvidence.SearchCall(validChildId, validRunId, UUID.randomUUID(), "call", "web_search",
                        true, List.of(), NOW)), false, 1, 0);
        when(evidence.find(context, validChildId, validRunId, 500, 10_000)).thenReturn(validEvidence);
        SubtaskGateService service = new SubtaskGateService(mock(FoundationPersistenceService.class),
                Clock.systemUTC(), runs, evidence);

        Map<UUID, TaskSourceEvidence> result = loadEvidence(service, parentId,
                List.of(task(failedChildId), task(validChildId)));

        assertThat(result).containsOnlyKeys(validChildId);
    }

    @Test
    void capsAggregateSourceItemsAndMarksTheInjectedEvidenceIncomplete() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        List<TaskSourceEvidence.Source> sources = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            String url = "https://example.test/" + "a".repeat(1_900) + index;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
            sources.add(new TaskSourceEvidence.Source("title-" + index, url, hash, "task-source:call:" + hash,
                    index == 0 ? "Verified source excerpt." : ""));
        }
        TaskSourceEvidence.SearchCall call = new TaskSourceEvidence.SearchCall(childId, runId, UUID.randomUUID(),
                "call", "web_search", true, sources, NOW);
        TaskSourceEvidence childEvidence = new TaskSourceEvidence(childId, runId, List.of(call), false, 1, 40);
        TaskRecord parent = new TaskRecord(parentId, "parent", "", TaskPhase.QUEUED, 0,
                "{\"teamId\":\"team\",\"teamRole\":\"LEADER\",\"orchestrationPhase\":\"PLAN\",\"inputJson\":{}}",
                "subject", "test", null, null, NOW, NOW, 0);

        String aggregate = SubtaskGateService.aggregateSpec(parent, List.of(task(childId)), Map.of(childId, childEvidence));
        JsonNode childSourceEvidence = JSON.readTree(aggregate).path("inputJson").path("childSourceEvidence").get(0);

        assertThat(childSourceEvidence.path("sources").size()).isLessThan(40);
        assertThat(childSourceEvidence.path("complete").asBoolean()).isFalse();
        assertThat(childSourceEvidence.path("sources").get(0).path("verified").asBoolean()).isTrue();
        assertThat(childSourceEvidence.path("sources").get(0).path("snippet").asText())
                .isEqualTo("Verified source excerpt.");
    }

    private static Map<UUID, TaskSourceEvidence> loadEvidence(SubtaskGateService service,
            UUID parentId, List<TaskRecord> children) throws Exception {
        Method method = SubtaskGateService.class.getDeclaredMethod("loadChildSourceEvidence", UUID.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, TaskSourceEvidence> result = (Map<UUID, TaskSourceEvidence>) method.invoke(service, parentId, children);
        return result;
    }

    private static TaskRecord task(UUID id) {
        return TaskRecord.draft(id, "child", "", "subject", "test", NOW);
    }

    private static TaskRunObservationRepository.TaskRunSnapshot succeededSnapshot() {
        return new TaskRunObservationRepository.TaskRunSnapshot("SUCCEEDED", "SUCCEEDED", NOW, NOW,
                "agentscope", null);
    }
}
