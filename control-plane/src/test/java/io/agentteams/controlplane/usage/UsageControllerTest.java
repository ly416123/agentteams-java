package io.agentteams.controlplane.usage;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
