package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.agentteams.controlplane.taskfile.TaskFileService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskFileReferenceValidatorTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final UUID PARENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void acceptsAnAvailableOutputFromASucceededChildInLatestParentTree() {
        TaskTreeService tree = mock(TaskTreeService.class);
        TaskFileService files = mock(TaskFileService.class);
        when(tree.findLatestForParent(CONTEXT, PARENT)).thenReturn(List.of(node(CHILD, "SUCCEEDED")));
        when(files.get(CHILD, FILE)).thenReturn(file(TaskFileRecord.OUTPUT, TaskFileRecord.AVAILABLE));

        new TaskFileReferenceValidator(tree, files).validate(CONTEXT, manifest());
    }

    @Test
    void acceptsAChildFileWhenTheParentAggregateRunDiffersFromThePlanningRun() {
        TaskTreeService tree = mock(TaskTreeService.class);
        TaskFileService files = mock(TaskFileService.class);
        when(tree.findLatestForParent(CONTEXT, PARENT)).thenReturn(List.of(node(CHILD, "SUCCEEDED")));
        when(files.get(CHILD, FILE)).thenReturn(file(TaskFileRecord.OUTPUT, TaskFileRecord.AVAILABLE));

        new TaskFileReferenceValidator(tree, files).validate(CONTEXT, manifest());
    }

    @Test
    void rejectsInputOrMissingFilesAndUnsuccessfulOrUnrelatedSources() {
        TaskTreeService tree = mock(TaskTreeService.class);
        TaskFileService files = mock(TaskFileService.class);
        when(tree.findLatestForParent(CONTEXT, PARENT)).thenReturn(List.of(node(CHILD, "FAILED")));
        when(files.get(CHILD, FILE)).thenReturn(file(TaskFileRecord.INPUT, TaskFileRecord.AVAILABLE));
        TaskFileReferenceValidator validator = new TaskFileReferenceValidator(tree, files);

        assertThatThrownBy(() -> validator.validate(CONTEXT, manifest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available OUTPUT");

        when(files.get(CHILD, FILE)).thenReturn(file(TaskFileRecord.OUTPUT, TaskFileRecord.MISSING));
        assertThatThrownBy(() -> validator.validate(CONTEXT, manifest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available OUTPUT");

        when(files.get(CHILD, FILE)).thenReturn(file(TaskFileRecord.OUTPUT, TaskFileRecord.AVAILABLE));
        assertThatThrownBy(() -> validator.validate(CONTEXT, manifest()))
                .hasMessageContaining("successful subtask file");
    }

    private static TaskResultManifest manifest() {
        return new TaskResultManifest(PARENT, RUN, "SUCCEEDED", "done", List.of(
                new TaskResultManifest.ArtifactMetadata("brief.pdf", "task-file://" + CHILD + "/" + FILE,
                        "application/pdf", 123, "ab".repeat(32), 0, "FINAL", TaskEventVisibility.REQUESTER,
                        CHILD, FILE)));
    }

    private static TaskTreeNode node(UUID taskId, String status) {
        return new TaskTreeNode(taskId, PARENT, 1, status, List.of(), Instant.EPOCH, Map.of());
    }

    private static TaskFileRecord file(String role, String status) {
        return new TaskFileRecord(FILE, CHILD, UUID.randomUUID(), role, "brief.pdf", "application/pdf",
                123, "ab".repeat(32), "tasks/child/files/file/brief.pdf", null, null,
                status, Instant.EPOCH, Instant.EPOCH);
    }
}
