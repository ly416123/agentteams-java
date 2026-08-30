package io.agentteams.controlplane.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DashboardResourcesControllerTest {
    @Mock private DashboardResourcesService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardResourcesController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void exposesAggregatedResourceCountsForTheAuthenticatedProject() throws Exception {
        when(service.summarize()).thenReturn(new DashboardResourcesService.Resources(
                new DashboardResourcesService.TaskCounts(24, 4, 3, 15, 2),
                new DashboardResourcesService.WorkerCounts(5, 1, 0, 1),
                new DashboardResourcesService.TeamCounts(3, 2)));

        mockMvc.perform(get("/api/v1/dashboard/resources").param("projectId", "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.total").value(24))
                .andExpect(jsonPath("$.tasks.succeeded").value(15))
                .andExpect(jsonPath("$.workers.ready").value(5))
                .andExpect(jsonPath("$.teams.active").value(2));
    }

    @Test
    void rejectsAProjectOutsideTheAuthenticatedScope() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/resources").param("projectId", "project-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void rejectsAnonymousReads() throws Exception {
        PrincipalContext.clear();

        mockMvc.perform(get("/api/v1/dashboard/resources"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
