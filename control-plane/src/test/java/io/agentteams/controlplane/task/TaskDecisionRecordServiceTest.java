package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskDecisionRecordServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @Test
    void storesOnlyAVisibleStructuredDecisionSummary() {
        TaskDecisionRecordRepository repository = mock(TaskDecisionRecordRepository.class);
        TaskDecisionRecordService service = new TaskDecisionRecordService(repository);
        TaskDecisionRecord record = new TaskDecisionRecord(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TaskEventVisibility.REQUESTER, "compare providers", "choose provider A", "latency", "budget",
                0.85, Instant.parse("2026-08-31T00:00:00Z"));

        service.append(CONTEXT, record);
        service.find(CONTEXT, record.taskId(), record.runId(), Set.of(TaskEventVisibility.REQUESTER));

        verify(repository).insert(CONTEXT, record);
        verify(repository).find(CONTEXT, record.taskId(), record.runId(), Set.of(TaskEventVisibility.REQUESTER));
    }

    @Test
    void rejectsCredentialAndChainOfThoughtMaterial() {
        TaskDecisionRecordRepository repository = mock(TaskDecisionRecordRepository.class);
        TaskDecisionRecordService service = new TaskDecisionRecordService(repository);
        assertThatThrownBy(() -> service.append(CONTEXT, new TaskDecisionRecord(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), TaskEventVisibility.REQUESTER, "password=secret", "choose", "evidence",
                "constraints", null, Instant.now()))).isInstanceOf(IllegalArgumentException.class);
    }
}
