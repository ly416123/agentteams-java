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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlPlaneCreateTaskToolTest {
    @Test
    void invokesTypedIdempotentControlPlaneCreate() {
        TaskCommandPort service = mock(TaskCommandPort.class);
        TaskCreationResult expected = new TaskCreationResult(UUID.randomUUID(), "DRAFT", 0);
        when(service.create(any(), any())).thenReturn(expected);
        CreateTaskIntent intent = new CreateTaskIntent("CREATE_TASK", "Login", "Implement login",
                List.of("java", "spring"), 50, false);

        TaskCreationResult actual = new ControlPlaneCreateTaskTool(service, new ObjectMapper()).create(intent);

        assertThat(actual).isSameAs(expected);
        verify(service).create(argThat(key -> key.startsWith("manager-")), argThat(input ->
                input.title().equals("Login") && input.specJson().contains("requiredCapabilities")));
    }
}
