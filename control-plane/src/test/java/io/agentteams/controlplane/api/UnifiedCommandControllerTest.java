package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.application.api.CommandStatus;
import io.agentteams.application.api.CommandResult;
import io.agentteams.controlplane.command.CommandDispatcher;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.domain.task.StaleTaskVersionException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class UnifiedCommandControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "alice");

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void acceptsACommandAndReturnsStableResult() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        ExecutionContextResolver resolver = mock(ExecutionContextResolver.class);
        UUID commandId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(resolver.resolve(any(Principal.class))).thenReturn(CONTEXT);
        when(dispatcher.dispatch(any(ExecutionContext.class), any())).thenReturn(
                new CommandResult(commandId, CommandStatus.ACCEPTED, false, taskId, 1, "trace-1"));
        PrincipalContext.set(new Principal("alice", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"),
                Set.of("task:operate")));
        MockMvc mvc = standaloneSetup(new UnifiedCommandController(dispatcher, resolver))
                .setControllerAdvice(new ApiErrorHandler()).build();

        ObjectNode request = JSON.createObjectNode();
        request.put("commandId", commandId.toString());
        request.put("commandType", "task.start");
        request.put("tenantId", "tenant-1");
        ObjectNode actor = request.putObject("actor");
        actor.put("type", "USER");
        actor.put("id", "alice");
        actor.put("channel", "CONSOLE");
        request.put("taskId", taskId.toString());
        request.put("aggregateType", "task");
        request.put("aggregateVersion", 0);
        request.putObject("payload");
        request.put("idempotencyKey", "command-1");
        request.put("traceId", "trace-1");
        request.put("occurredAt", "2026-09-28T03:00:00Z");

        mvc.perform(post("/api/v1/commands")
                        .contentType("application/json")
                        .content(JSON.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andExpect(jsonPath("$.taskId").value(taskId.toString()));
    }

    @Test
    void rejectsRequestsWithoutAnAuthenticatedPrincipal() throws Exception {
        MockMvc mvc = standaloneSetup(new UnifiedCommandController(mock(CommandDispatcher.class),
                mock(ExecutionContextResolver.class))).setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(post("/api/v1/commands").contentType("application/json")
                        .content(JSON.writeValueAsBytes(commandJson(UUID.randomUUID(), UUID.randomUUID()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void mapsAggregateVersionConflictsToConflict() throws Exception {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        ExecutionContextResolver resolver = mock(ExecutionContextResolver.class);
        when(resolver.resolve(any(Principal.class))).thenReturn(CONTEXT);
        when(dispatcher.dispatch(any(ExecutionContext.class), any()))
                .thenThrow(new StaleTaskVersionException(0, 1));
        PrincipalContext.set(new Principal("alice", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"),
                Set.of("task:operate")));
        MockMvc mvc = standaloneSetup(new UnifiedCommandController(dispatcher, resolver))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(post("/api/v1/commands").contentType("application/json")
                        .content(JSON.writeValueAsBytes(commandJson(UUID.randomUUID(), UUID.randomUUID()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private static ObjectNode commandJson(UUID commandId, UUID taskId) {
        ObjectNode request = JSON.createObjectNode();
        request.put("commandId", commandId.toString());
        request.put("commandType", "task.start");
        request.put("tenantId", "tenant-1");
        ObjectNode actor = request.putObject("actor");
        actor.put("type", "USER");
        actor.put("id", "alice");
        actor.put("channel", "CONSOLE");
        request.put("taskId", taskId.toString());
        request.put("aggregateType", "task");
        request.put("aggregateVersion", 0);
        request.putObject("payload");
        request.put("idempotencyKey", "command-1");
        request.put("traceId", "trace-1");
        request.put("occurredAt", "2026-09-28T03:00:00Z");
        return request;
    }
}
