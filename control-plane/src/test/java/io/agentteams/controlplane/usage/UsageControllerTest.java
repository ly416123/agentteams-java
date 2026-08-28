package io.agentteams.controlplane.usage;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock
    private UsageQueryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(service)).build();
    }

    @Test
    void returnsSummaryAndProviderModelBreakdown() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(service.summarize(from, to)).thenReturn(new UsageQueryService.UsageSummary(from, to,
                new UsageQueryService.UsageTotals(3, 1, 60, 20, 18.25),
                List.of(new UsageQueryService.UsageGroup("deepseek", "deepseek-chat", 3, 1,
                        60, 20, 18.25))));

        mockMvc.perform(get("/api/v1/usage/summary")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(3))
                .andExpect(jsonPath("$.failures").value(1))
                .andExpect(jsonPath("$.promptTokens").value(60))
                .andExpect(jsonPath("$.completionTokens").value(20))
                .andExpect(jsonPath("$.averageLatencyMillis").value(18.25))
                .andExpect(jsonPath("$.byProviderModel[0].provider").value("deepseek"))
                .andExpect(jsonPath("$.byProviderModel[0].model").value("deepseek-chat"));
    }

    @Test
    void rejectsMalformedTimeParameters() throws Exception {
        mockMvc.perform(get("/api/v1/usage/summary").param("from", "not-an-instant"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsExplicitStatusGroupingWithLimit() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(service.summarize(from, to, "status", 1)).thenReturn(new UsageQueryService.UsageSummary(from, to,
                new UsageQueryService.UsageTotals(3, 1, 60, 20, 18.25),
                List.of(new UsageQueryService.UsageGroup(null, null, 3, 1, 60, 20, 18.25, "SUCCESS"))));

        mockMvc.perform(get("/api/v1/usage/summary")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("groupBy", "status")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("status"))
                .andExpect(jsonPath("$.groups[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.groups[0].calls").value(3));
        verify(service).summarize(from, to, "status", 1);
    }

    @Test
    void returnsDimensionCompletenessWithoutDimensionValues() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        when(service.completeness(from, to)).thenReturn(new UsageQueryService.UsageCompleteness(from, to, 12,
                List.of(new UsageQueryService.UsageDimensionCompleteness("workerId", 9, 3,
                        new BigDecimal("0.750000")))));

        mockMvc.perform(get("/api/v1/usage/dimensions/completeness")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").exists())
                .andExpect(jsonPath("$.to").exists())
                .andExpect(jsonPath("$.totalCalls").value(12))
                .andExpect(jsonPath("$.dimensions[0].name").value("workerId"))
                .andExpect(jsonPath("$.dimensions[0].present").value(9))
                .andExpect(jsonPath("$.dimensions[0].missing").value(3))
                .andExpect(jsonPath("$.dimensions[0].coverage").value(0.750000))
                .andExpect(jsonPath("$.dimensions[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.dimensions[0].dimensionValue").doesNotExist());
        verify(service).completeness(from, to);
    }
}
