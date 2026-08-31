package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProgressSnapshot;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.task.TaskProcessEventService;
import io.agentteams.controlplane.task.TaskProgressService;
import io.agentteams.controlplane.task.TaskResultManifestService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TaskProcessControllerTest {
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void exposesReplayProgressAndTerminalResultWithRequesterVisibilityByDefault() throws Exception {
        TaskProcessEventService events = Mockito.mock(TaskProcessEventService.class);
        TaskProgressService progress = Mockito.mock(TaskProgressService.class);
        TaskResultManifestService results = Mockito.mock(TaskResultManifestService.class);
        ExecutionContextResolver resolver = Mockito.mock(ExecutionContextResolver.class);
        Principal principal = new Principal("user-1", new AuthorizationService.Scope("tenant-1", "project-1", "team-1"), Set.of());
        PrincipalContext.set(principal);
        when(resolver.resolve(principal)).thenReturn(CONTEXT);
        when(events.replay(CONTEXT, TASK_ID, RUN_ID, 0, Set.of(TaskEventVisibility.REQUESTER), 100))
                .thenReturn(List.of(event()));
        when(progress.snapshot(CONTEXT, TASK_ID, RUN_ID, "EXECUTION"))
                .thenReturn(new TaskProgressSnapshot("EXECUTION", 1, 1, 100, ""));
        when(results.get(CONTEXT, TASK_ID, RUN_ID, Set.of(TaskEventVisibility.REQUESTER)))
                .thenReturn(java.util.Optional.of(new TaskResultManifest(TASK_ID, RUN_ID, "SUCCEEDED", "done", List.of())));

        MockMvc mvc = standaloneSetup(new TaskProcessController(events, progress, results, resolver)).build();

        mvc.perform(get("/api/v1/tasks/{taskId}/runs/{runId}/process-events", TASK_ID, RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/{taskId}/runs/{runId}/progress", TASK_ID, RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/{taskId}/runs/{runId}/result", TASK_ID, RUN_ID))
                .andExpect(status().isOk());
    }

    private static TaskProcessEvent event() {
        return new TaskProcessEvent(UUID.randomUUID(), TASK_ID, RUN_ID, 1, "PROGRESS",
                TaskEventVisibility.REQUESTER, Instant.parse("2026-08-31T00:00:00Z"), "corr-1", "{\"progress\":100}", null);
    }
}
