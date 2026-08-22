package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixTaskCommandHandlerTest {
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

    private static MatrixIdentity identity(Set<String> permissions) {
        return new MatrixIdentity("@alice:agentteams.test",
                new Principal("alice", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                        permissions));
    }
}
