package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskStateConsistencyServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void recordsCurrentFindingsAndResolvesFindingsThatDisappeared() {
        TaskStateConsistencyRepository repository = mock(TaskStateConsistencyRepository.class);
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TaskStateConsistencySnapshot snapshot = snapshot(taskId, runId);
        TaskStateConsistencyChecker checker = mock(TaskStateConsistencyChecker.class);
        TaskStateConsistencyIssue issue = new TaskStateConsistencyIssue(taskId, runId, "org-1", "tenant-1",
                "TASK_RUN_STATUS_MISMATCH", "SUCCEEDED", "RUNNING", null, "status mismatch", NOW);
        when(repository.findSnapshots(NOW.minusSeconds(3600), 100)).thenReturn(List.of(snapshot));
        when(checker.check(snapshot)).thenReturn(List.of(issue));
        when(repository.findOpenIssueTypes(taskId, runId)).thenReturn(List.of("TASK_RUN_STATUS_MISMATCH",
                "TERMINAL_LEASE_ACTIVE"));

        TaskStateConsistencyService service = new TaskStateConsistencyService(repository, checker);

        assertThat(service.reconcile(NOW, java.time.Duration.ofHours(1), 100))
                .isEqualTo(new TaskStateConsistencyService.ReconcileResult(1, 1, 1, 0));
        verify(repository).upsertIssue(issue, NOW);
        verify(repository).resolveIssue(taskId, runId, "TERMINAL_LEASE_ACTIVE", NOW);
    }

    @Test
    void exposesOpenFindingsForInternalConsumers() {
        TaskStateConsistencyRepository repository = mock(TaskStateConsistencyRepository.class);
        List<TaskStateConsistencyIssueRecord> records = List.of();
        when(repository.findOpenIssues(10)).thenReturn(records);

        assertThat(new TaskStateConsistencyService(repository, new TaskStateConsistencyChecker())
                .findOpenIssues(10)).isSameAs(records);
    }

    private static TaskStateConsistencySnapshot snapshot(UUID taskId, UUID runId) {
        return new TaskStateConsistencySnapshot(taskId, runId, "org-1", "tenant-1", "SUCCEEDED", "RUNNING",
                null, 0, 0, 0, -1, 0, NOW);
    }
}
