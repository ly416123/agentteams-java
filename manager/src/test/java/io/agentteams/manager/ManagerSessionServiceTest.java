package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManagerSessionServiceTest {
    @Test
    void validatesModelOutputBeforeCallingCreateTaskTool() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ModelProvider.ModelResponse("{\"intent\":\"CREATE_TASK\",\"title\":\"Login\","
                        + "\"description\":\"Implement login\",\"required_capabilities\":[\"java\"],"
                        + "\"priority\":50,\"requires_approval\":false}", "deepseek-chat", 1, 2));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> ((CreateTaskIntent) input).title()))));

        assertThat(service.handleCreateTask("create a login task",
                new ManagerToolRegistry.ToolContext(Set.of("task:create"), false))).isEqualTo("Login");
    }

    @Test
    void invalidOutputCannotTriggerTool() {
        ModelProvider provider = mock(ModelProvider.class);
        when(provider.complete(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ModelProvider.ModelResponse("not-json", "deepseek-chat", 0, 0));
        ManagerSessionService service = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> { throw new AssertionError("must not run"); }))));

        assertThatThrownBy(() -> service.handleCreateTask("bad", new ManagerToolRegistry.ToolContext(
                Set.of("task:create"), false))).isInstanceOf(InvalidModelOutputException.class);
    }
}
