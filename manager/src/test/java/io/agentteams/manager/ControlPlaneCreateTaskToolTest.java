package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.application.api.TaskCommandPort.TaskCreationResult;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

class ControlPlaneCreateTaskToolTest {
    @AfterEach
    void clearManagerContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void invokesTypedIdempotentControlPlaneCreate() {
        TaskCommandPort service = mock(TaskCommandPort.class);
        TaskCreationResult expected = new TaskCreationResult(UUID.randomUUID(), "DRAFT", 0);
        when(service.create(any(), any())).thenReturn(expected);
        CreateTaskIntent intent = new CreateTaskIntent("CREATE_TASK", "Login", "Implement login",
                List.of("java", "spring"), 50, false);
        ManagerRequestContext.set(new ManagerPrincipal("alice", "tenant-a", "project-a", "team-a",
                Set.of("task:create")), "signed-bearer-token");

        TaskCreationResult actual = new ControlPlaneCreateTaskTool(service, new ObjectMapper()).create(intent,
                new ManagerToolRegistry.ToolContext(Set.of("task:create"), false, "tenant-a", "project-a",
                        null, null, "team-a", "create_task", null, null, UUID.randomUUID().toString()));

        assertThat(actual).isSameAs(expected);
        verify(service).create(argThat(key -> key.startsWith("manager-")), argThat(input ->
                input.title().equals("Login")
                        && input.specJson().contains("requiredCapabilities")
                        && input.specJson().contains("managerSessionId")
                        && input.specJson().contains("\"tenant\":\"tenant-a\"")
                        && input.specJson().contains("\"project\":\"project-a\"")
                        && input.specJson().contains("\"team\":\"team-a\"")));
    }
}
