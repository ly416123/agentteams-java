package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.CommandResult;
import io.agentteams.application.api.CommandStatus;
import io.agentteams.controlplane.command.CommandDispatcher;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

class MatrixTaskCommandHandlerTest {
    @AfterEach
    void clearPrincipal() {
        io.agentteams.controlplane.security.PrincipalContext.clear();
    }

    @Test
    void createsScopedTaskForAuthorizedMatrixStartCommand() {
        TaskCommandPort tasks = mock(TaskCommandPort.class);
        UUID taskId = UUID.randomUUID();
        when(tasks.create(any(), any())).thenReturn(new TaskCommandPort.TaskCreationResult(taskId, "DRAFT", 0));
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(tasks);

        String response = handler.handle(identity(Set.of("task:create")),
                new MatrixCommand.Start("investigate incident"));

        assertThat(response).isEqualTo("created task " + taskId);
        verify(tasks).create(any(), any());
    }

    @Test
    void rejectsMatrixStartWithoutCreatePermission() {
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(mock(TaskCommandPort.class));

        assertThatThrownBy(() -> handler.handle(identity(Set.of("task:read")),
                new MatrixCommand.Start("forbidden")))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void returnsScopedTaskStatusForAuthorizedMatrixUser() {
        TaskCommandPort tasks = mock(TaskCommandPort.class);
        TaskService taskService = mock(TaskService.class);
        UUID taskId = UUID.randomUUID();
        TaskRecord task = task(taskId, TaskPhase.QUEUED, 2);
        when(taskService.get(taskId)).thenReturn(task);
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(tasks, taskService);

        String response = handler.handle(identity(Set.of("task:read")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.STATUS, taskId));

        assertThat(response).isEqualTo("task " + taskId + ": QUEUED v2 investigate incident");
        verify(taskService).get(taskId);
    }

    @Test
    void cancelsScopedTaskWithCurrentVersionForAuthorizedMatrixUser() {
        TaskCommandPort tasks = mock(TaskCommandPort.class);
        TaskService taskService = mock(TaskService.class);
        UUID taskId = UUID.randomUUID();
        TaskRecord current = task(taskId, TaskPhase.QUEUED, 3);
        TaskRecord cancelled = task(taskId, TaskPhase.CANCELLED, 4);
        when(taskService.get(taskId)).thenReturn(current);
        when(taskService.cancel(any(), any(Long.class), any(), any(), any())).thenReturn(cancelled);
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(tasks, taskService);

        String response = handler.handle(identity(Set.of("task:cancel")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.CANCEL, taskId));

        assertThat(response).isEqualTo("cancelled task " + taskId);
        verify(taskService).cancel(any(), org.mockito.ArgumentMatchers.eq(3L), any(),
                org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.eq("matrix"));
    }

    @Test
    void mapsRetryPauseApproveAndRejectToAuthorizedTaskOperations() {
        TaskCommandPort tasks = mock(TaskCommandPort.class);
        TaskService taskService = mock(TaskService.class);
        UUID taskId = UUID.randomUUID();
        when(taskService.get(taskId)).thenReturn(task(taskId, TaskPhase.FAILED, 4));
        when(taskService.retry(any(), any(Long.class), any(), any(), any()))
                .thenReturn(task(taskId, TaskPhase.QUEUED, 5));
        when(taskService.pause(any(), any(Long.class), any(), any(), any()))
                .thenReturn(task(taskId, TaskPhase.PAUSED, 5));
        when(taskService.approve(any(), any(Long.class), any(), any(), any()))
                .thenReturn(task(taskId, TaskPhase.DRAFT, 5,
                        "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"},\"approvalGranted\":true}"));
        when(taskService.reject(any(), any(Long.class), any(), any(), any()))
                .thenReturn(task(taskId, TaskPhase.REJECTED, 5));
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(tasks, taskService);

        assertThat(handler.handle(identity(Set.of("task:retry")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.RETRY, taskId)))
                .isEqualTo("retried task " + taskId);
        assertThat(handler.handle(identity(Set.of("task:pause")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.PAUSE, taskId)))
                .isEqualTo("paused task " + taskId);
        assertThat(handler.handle(identity(Set.of("task:approve")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.APPROVE, taskId)))
                .isEqualTo("approved task " + taskId);
        assertThat(handler.handle(identity(Set.of("task:reject")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.REJECT, taskId)))
                .isEqualTo("rejected task " + taskId);
    }

    @Test
    void rejectsLifecycleMutationWithoutItsSpecificPermission() {
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(mock(TaskCommandPort.class),
                mock(TaskService.class));

        assertThatThrownBy(() -> handler.handle(identity(Set.of("task:read")),
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.RETRY, UUID.randomUUID())))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void restoresMatrixPrincipalContextAfterCommand() {
        TaskCommandPort tasks = mock(TaskCommandPort.class);
        when(tasks.create(any(), any())).thenReturn(new TaskCommandPort.TaskCreationResult(
                UUID.randomUUID(), "DRAFT", 0));
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(tasks);

        handler.handle(identity(Set.of("task:create")), new MatrixCommand.Start("scoped"));

        assertThat(io.agentteams.controlplane.security.PrincipalContext.current()).isEmpty();
    }

    @Test
    void dispatchesTaskRoomPauseWithCurrentAggregateVersionAndStableMatrixKey() {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        ExecutionContextResolver contexts = mock(ExecutionContextResolver.class);
        TaskService taskService = mock(TaskService.class);
        UUID taskId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        Principal principal = new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("task:pause"));
        MatrixIdentity identity = new MatrixIdentity("@alice:agentteams.test", principal);
        MatrixRoomBinding binding = new MatrixRoomBinding(UUID.randomUUID(), "org-a", "tenant-a",
                "!task:example.org", MatrixRoomContextType.TASK, taskId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), MatrixRoomBindingStatus.ACTIVE,
                "alice", Instant.parse("2026-09-28T04:00:00Z"), Instant.parse("2026-09-28T04:00:00Z"), 0);
        when(taskService.get(taskId)).thenReturn(task(taskId, TaskPhase.QUEUED, 7));
        when(contexts.resolve(principal)).thenReturn(new ExecutionContext("org-a", "tenant-a", "project-a",
                "team-a", "alice"));
        when(dispatcher.dispatch(any(), any())).thenReturn(new CommandResult(UUID.randomUUID(),
                CommandStatus.ACCEPTED, false, taskId, 8, "trace"));
        MatrixTaskCommandHandler handler = new MatrixTaskCommandHandler(dispatcher, contexts, taskService);

        String response = handler.handle(identity, binding,
                new MatrixCommand.TaskAction(MatrixCommand.TaskAction.Action.PAUSE, taskId), "txn-1", "event-1");

        assertThat(response).contains(taskId.toString());
        var captured = org.mockito.ArgumentCaptor.forClass(io.agentteams.application.api.CommandEnvelope.class);
        verify(dispatcher).dispatch(any(ExecutionContext.class), captured.capture());
        assertThat(captured.getValue().commandType()).isEqualTo("task.pause");
        assertThat(captured.getValue().aggregateVersion()).isEqualTo(7);
        assertThat(captured.getValue().idempotencyKey()).isEqualTo("matrix:txn-1:event-1");
        verify(taskService).get(taskId);
    }

    private static TaskRecord task(UUID id, TaskPhase phase, long version) {
        return task(id, phase, version,
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}");
    }

    private static TaskRecord task(UUID id, TaskPhase phase, long version, String specJson) {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        return new TaskRecord(id, "investigate incident", "description", phase, 0,
                specJson,
                "alice", "matrix", null, null, now, now, version);
    }

    private static MatrixIdentity identity(Set<String> permissions) {
        return new MatrixIdentity("@alice:agentteams.test",
                new Principal("alice", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                        permissions));
    }
}
