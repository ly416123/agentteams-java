package io.agentteams.controlplane.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
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
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardSummaryController(usage)).build();
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
}
