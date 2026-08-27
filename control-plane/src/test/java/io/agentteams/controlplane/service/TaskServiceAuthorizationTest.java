package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.domain.task.TaskTransitionService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskServiceAuthorizationTest {
    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private final ResourceAuthorizationService authorization = mock(ResourceAuthorizationService.class);

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void deniedTaskCreateDoesNotPersist() {
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        doThrow(new AuthorizationException("permission denied: TASK_CREATE"))
                .when(authorization).require(ResourceAction.TASK_CREATE, scope);

        TaskService service = new TaskService(persistence, new IdempotencyService(), new TaskTransitionService(),
                Clock.systemUTC(), io.agentteams.controlplane.observability.TaskMetricsPort.noop(), null,
                authorization);

        assertThatThrownBy(() -> service.create("create-key",
                new TaskService.TaskInput("title", "description", scopedSpec(), "alice", "rest")))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).createTask(any(CreateTaskCommand.class));
        verify(authorization).require(ResourceAction.TASK_CREATE, scope);
    }

    @Test
    void authenticatedCreateChecksScopeBeforeResourceRole() {
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        TaskService service = new TaskService(persistence, new IdempotencyService(), new TaskTransitionService(),
                Clock.systemUTC(), io.agentteams.controlplane.observability.TaskMetricsPort.noop(), null,
                authorization);

        assertThatThrownBy(() -> service.create("create-key",
                new TaskService.TaskInput("title", "description",
                        "{\"scope\":{\"tenant\":\"tenant-b\",\"project\":\"project-a\",\"team\":\"team-a\"}}",
                        "alice", "rest")))
                .isInstanceOf(AuthorizationException.class);
        verify(authorization, never()).require(eq(ResourceAction.TASK_CREATE),
                any(AuthorizationService.Scope.class));
        verify(persistence, never()).createTask(any(CreateTaskCommand.class));
    }

    @Test
    void deniedTaskMutationDoesNotTransition() {
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId)));
        doThrow(new AuthorizationException("permission denied: TASK_OPERATE"))
                .when(authorization).require(ResourceAction.TASK_OPERATE, scope);
        TaskService service = service();

        assertThatThrownBy(() -> service.cancel(taskId, 0, "cancel-key", "alice", "rest"))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).transitionTask(any(), any(), any(Long.class), any(), any(), any(), any());
        verify(authorization).require(ResourceAction.TASK_OPERATE, scope);
    }

    @Test
    void deniedTaskApprovalDoesNotUpdateSpec() {
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
        PrincipalContext.set(new Principal("alice", scope, Set.of()));
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId)));
        doThrow(new AuthorizationException("permission denied: TASK_APPROVE"))
                .when(authorization).require(ResourceAction.TASK_APPROVE, scope);
        TaskService service = service();

        assertThatThrownBy(() -> service.reject(taskId, 0, "reject-key", "alice", "rest"))
                .isInstanceOf(AuthorizationException.class);
        verify(persistence, never()).transitionTaskWithSpec(any(), any(), any(), any(Long.class), any(), any(), any(),
                any(), any());
        verify(authorization).require(ResourceAction.TASK_APPROVE, scope);
    }

    private static String scopedSpec() {
        return "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}";
    }

    private TaskService service() {
        return new TaskService(persistence, new IdempotencyService(), new TaskTransitionService(),
                Clock.systemUTC(), io.agentteams.controlplane.observability.TaskMetricsPort.noop(), null,
                authorization);
    }

    private static TaskRecord task(UUID id) {
        Instant now = Instant.EPOCH;
        return new TaskRecord(id, "task", "description", TaskPhase.DRAFT, 0, scopedSpec(),
                "alice", "rest", null, null, now, now, 0);
    }
}
