package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.worker.WorkerOperationService;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentListControllerTest {
    private MockMvc mvc;
    private AgentService service;

    @BeforeEach
    void setUp() {
        service = mock(AgentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service, mock(WorkerOperationService.class)))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("actor-a", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("agent:read")));
    }

    @AfterEach
    void clearContext() { PrincipalContext.clear(); }

    @Test
    void listsOnlyAgentsReturnedForCurrentScopeAsCursorPage() throws Exception {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        AgentRecord agent = new AgentRecord(UUID.randomUUID(), "worker-a", WorkerType.LEADER, AgentPhase.READY, "qwenpaw", "{}",
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}",
                now, now, 0, "analysis-template");
        when(service.list(any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CursorPage<>(List.of(agent), null, false, now));

        mvc.perform(get("/api/v1/agents").param("pageSize", "20").param("search", "worker")
                .param("status", "READY").param("projectId", "project-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("worker-a"))
                .andExpect(jsonPath("$.items[0].phase").value("READY"))
                .andExpect(jsonPath("$.items[0].workerType").value("LEADER"))
                .andExpect(jsonPath("$.items[0].templateName").value("analysis-template"));

        verify(service).list(any(), org.mockito.ArgumentMatchers.eq("READY"),
                org.mockito.ArgumentMatchers.eq("worker"));
        verify(service).requireProjectScope("project-uuid");
    }
}
