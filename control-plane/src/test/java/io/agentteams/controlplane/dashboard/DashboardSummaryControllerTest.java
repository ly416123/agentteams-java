package io.agentteams.controlplane.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DashboardSummaryControllerTest {
    @Mock private UsageQueryService usage;
    @Mock private ProjectRepository projects;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardSummaryController(usage, projects))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void exposesCanonicalUsageTotalsAndProviderModelGroups() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(usage.summarizeForScope("tenant-a", "project-a", null, null, null, null))
                .thenReturn(new UsageQueryService.UsageSummary(from, to,
                new UsageQueryService.UsageTotals(3, 1, 10, 20, 12.5),
                List.of(new UsageQueryService.UsageGroup("deepseek", "chat", 3, 1, 10, 20, 12.5))));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(3))
                .andExpect(jsonPath("$.failures").value(1))
                .andExpect(jsonPath("$.byProviderModel[0].provider").value("deepseek"));
    }

    @Test
    void exposesRequestedOperationalDimensionWithoutChangingLegacyShape() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(usage.summarizeForScope("tenant-a", "project-a", from, to, "worker", 5))
                .thenReturn(new UsageQueryService.UsageSummary(from, to,
                new UsageQueryService.UsageTotals(2, 0, 10, 20, 1.25),
                List.of(new UsageQueryService.UsageGroup(null, null, 2, 0, 10, 20, 1.25,
                        15, null, "worker", "worker-a"))));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("groupBy", "worker")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("worker"))
                .andExpect(jsonPath("$.groups[0].dimension").value("worker"))
                .andExpect(jsonPath("$.groups[0].dimensionValue").value("worker-a"));
    }

    @Test
    void scopesProjectDashboardToAuthenticatedTenantAndProject() throws Exception {
        when(usage.summarizeForScope("tenant-a", "project-a", null, null, null, null))
                .thenReturn(new UsageQueryService.UsageSummary(Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                        new UsageQueryService.UsageTotals(2, 0, 10, 20, 1.25), List.of()));

        mockMvc.perform(get("/api/v1/dashboard/summary").param("projectId", "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(2));
    }

    @Test
    void acceptsAuthorizedProjectUuidAndResolvesItToAuthenticatedProjectScope() throws Exception {
        UUID projectId = UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447");
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        when(projects.findProject("tenant-a", projectId))
                .thenReturn(java.util.Optional.of(ProjectRecord.create(projectId, "tenant-a", "project-a", "alice", now)));
        when(projects.findMembership("tenant-a", projectId, "alice"))
                .thenReturn(java.util.Optional.of(ProjectMembershipRecord.create("tenant-a", projectId, "alice",
                        ProjectRole.DEVELOPER, now)));
        when(usage.summarizeForScope("tenant-a", projectId.toString(), null, null, null, null))
                .thenReturn(new UsageQueryService.UsageSummary(Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                        new UsageQueryService.UsageTotals(4, 0, 10, 20, 1.25), List.of()));

        mockMvc.perform(get("/api/v1/dashboard/summary").param("projectId", projectId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(4));
    }

    @Test
    void rejectsAnonymousDashboardReads() throws Exception {
        PrincipalContext.clear();

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
