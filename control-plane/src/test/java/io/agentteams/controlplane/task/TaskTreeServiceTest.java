package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTreeServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @Test
    void upsertsAndReturnsAStableTaskTree() {
        TaskTreeRepository repository = mock(TaskTreeRepository.class);
        UUID runId = UUID.randomUUID();
        TaskTreeNode root = new TaskTreeNode(UUID.randomUUID(), null, 0, "RUNNING", List.of(), Instant.parse("2026-08-31T00:00:00Z"));
        TaskTreeNode child = new TaskTreeNode(UUID.randomUUID(), root.taskId(), 1, "PENDING", List.of(root.taskId()), Instant.parse("2026-08-31T00:00:01Z"));
        TaskTreeService service = new TaskTreeService(repository);

        service.upsert(CONTEXT, runId, root);
        service.upsert(CONTEXT, runId, child);
        service.find(CONTEXT, runId);

        verify(repository).upsert(CONTEXT, runId, root);
        verify(repository).upsert(CONTEXT, runId, child);
        verify(repository).find(CONTEXT, runId);
    }

    @Test
    void rejectsSelfParentAndSelfDependency() {
        TaskTreeRepository repository = mock(TaskTreeRepository.class);
        TaskTreeService service = new TaskTreeService(repository);
        UUID taskId = UUID.randomUUID();
        TaskTreeNode invalid = new TaskTreeNode(taskId, taskId, 0, "PENDING", List.of(taskId), Instant.now());

        assertThatThrownBy(() -> service.upsert(CONTEXT, UUID.randomUUID(), invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
