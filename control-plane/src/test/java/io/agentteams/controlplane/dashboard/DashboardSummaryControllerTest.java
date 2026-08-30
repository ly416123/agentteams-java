package io.agentteams.controlplane.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrincipalContext.clear();
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardSummaryController(usage)).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void exposesCanonicalUsageTotalsAndProviderModelGroups() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(usage.summarize(null, null)).thenReturn(new UsageQueryService.UsageSummary(from, to,
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
        when(usage.summarize(from, to, "worker", 5)).thenReturn(new UsageQueryService.UsageSummary(from, to,
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
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        when(usage.summarizeForScope("tenant-a", "project-a", null, null, null, null))
                .thenReturn(new UsageQueryService.UsageSummary(Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                        new UsageQueryService.UsageTotals(2, 0, 10, 20, 1.25), List.of()));

        mockMvc.perform(get("/api/v1/dashboard/summary").param("projectId", "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(2));
    }
}
