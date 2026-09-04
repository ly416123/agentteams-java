package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceScopeTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
            Set.of("task:read", "task:write"));

    @Mock
    private FoundationPersistenceService persistence;

    @Mock
    private ResourceScopeRepository resourceScopes;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(persistence, new IdempotencyService(), new TaskTransitionService(),
                Clock.fixed(NOW, ZoneOffset.UTC), io.agentteams.observability.TaskMetricsPort.noop(),
                resourceScopes);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void bindsAuthenticatedTaskCreationToTheCallerProject() {
        UUID taskId = UUID.randomUUID();
        TaskRecord created = task(taskId);
        when(persistence.createTask(any())).thenReturn(created);

        service.create("task-key", new TaskService.TaskInput("task", "description", "{}", "alice", "rest"));

        verify(resourceScopes).bind("TASK", taskId, PRINCIPAL, NOW);
    }

    @Test
    void rejectsAuthenticatedReadOutsideTheCallerProject() {
        UUID taskId = UUID.randomUUID();
        when(persistence.findTask(taskId)).thenReturn(java.util.Optional.of(task(taskId)));
        doThrow(new AuthorizationException("resource is outside caller project"))
                .when(resourceScopes).requireVisible("TASK", taskId);

        assertThatThrownBy(() -> service.get(taskId))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("resource is outside caller project");
    }

    private static TaskRecord task(UUID id) {
        return new TaskRecord(id, "task", "description", TaskPhase.DRAFT, 0, "{}",
                "alice", "rest", null, null, NOW, NOW, 0);
    }
}
