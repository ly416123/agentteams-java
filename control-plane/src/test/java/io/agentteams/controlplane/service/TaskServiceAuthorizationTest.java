package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentteams.controlplane.persistence.CreateTaskCommand;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Clock;
import java.util.Set;
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

    private static String scopedSpec() {
        return "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}";
    }
}
