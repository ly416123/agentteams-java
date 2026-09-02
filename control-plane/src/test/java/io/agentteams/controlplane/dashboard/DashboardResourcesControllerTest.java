package io.agentteams.controlplane.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
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
    @Mock private ProjectRepository projects;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardResourcesController(service, projects))
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
    void acceptsAuthorizedProjectUuidForTheAuthenticatedProject() throws Exception {
        UUID projectId = UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447");
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        when(projects.findProject("tenant-a", projectId))
                .thenReturn(java.util.Optional.of(ProjectRecord.create(projectId, "tenant-a", "project-a", "alice", now)));
        when(projects.findMembership("tenant-a", projectId, "alice"))
                .thenReturn(java.util.Optional.of(ProjectMembershipRecord.create("tenant-a", projectId, "alice",
                        ProjectRole.DEVELOPER, now)));
        when(service.summarize()).thenReturn(new DashboardResourcesService.Resources(
                new DashboardResourcesService.TaskCounts(1, 0, 0, 1, 0),
                new DashboardResourcesService.WorkerCounts(1, 0, 0, 0),
                new DashboardResourcesService.TeamCounts(1, 1)));

        mockMvc.perform(get("/api/v1/dashboard/resources").param("projectId", projectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.total").value(1));
    }

    @Test
    void rejectsAnonymousReads() throws Exception {
        PrincipalContext.clear();

        mockMvc.perform(get("/api/v1/dashboard/resources"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
