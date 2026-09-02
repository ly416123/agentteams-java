package io.agentteams.controlplane.usage;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock
    private UsageQueryService service;

    @Mock
    private AuditRecorder audit;

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

    @Test
    void forwardsOperationalFiltersToTheScopedUsageQuery() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        UsageQueryService.UsageFilters filters = new UsageQueryService.UsageFilters(
                "task-a", "deepseek", "deepseek-chat");
        when(service.summarize(from, to, "task", 20, filters)).thenReturn(new UsageQueryService.UsageSummary(
                from, to, new UsageQueryService.UsageTotals(1, 0, 10, 5, 0), List.of()));

        mockMvc.perform(get("/api/v1/usage/summary")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("groupBy", "task")
                        .param("limit", "20")
                        .param("taskId", "task-a")
                        .param("provider", "deepseek")
                        .param("model", "deepseek-chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calls").value(1));
        verify(service).summarize(from, to, "task", 20, filters);
    }

    @Test
    void forwardsOffsetAndReturnsNextOffsetForPaginatedUsage() throws Exception {
        Instant from = Instant.parse("2026-08-23T00:00:00Z");
        Instant to = Instant.parse("2026-08-23T01:00:00Z");
        UsageQueryService.UsageFilters filters = UsageQueryService.UsageFilters.empty();
        when(service.summarize(from, to, "provider_model", 20, filters, 10)).thenReturn(
                new UsageQueryService.UsageSummary(from, to,
                        new UsageQueryService.UsageTotals(21, 0, 100, 40, 1.25), List.of(
                                new UsageQueryService.UsageGroup("deepseek", "deepseek-chat", 1, 0,
                                        10, 5, 0.1)),
                        10, 20, true));

        mockMvc.perform(get("/api/v1/usage/summary")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("groupBy", "provider_model")
                        .param("limit", "20")
                        .param("offset", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(10))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.nextOffset").value(30));
        verify(service).summarize(from, to, "provider_model", 20, filters, 10);
    }

    @Test
    void exportsScopedUsageAsCsv() throws Exception {
        UsageQueryService.UsageFilters filters = new UsageQueryService.UsageFilters(
                "task-a", "deepseek", "deepseek-chat");
        when(service.summarize(null, null, "provider_model", 1000, filters)).thenReturn(
                new UsageQueryService.UsageSummary(Instant.parse("2026-08-23T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z"),
                        new UsageQueryService.UsageTotals(2, 1, 20, 10, 1.25),
                        List.of(new UsageQueryService.UsageGroup("deepseek", "deepseek-chat", 2, 1,
                                20, 10, 1.25))));

        mockMvc.perform(get("/api/v1/usage/export")
                        .param("taskId", "task-a")
                        .param("provider", "deepseek")
                        .param("model", "deepseek-chat"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=usage.csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"deepseek\",\"deepseek-chat\",\"\",\"\",\"\",\"2\",\"1\"")));
        verify(service).summarize(null, null, "provider_model", 1000, filters);
    }

    @Test
    void auditsUsageExportWithoutRecordingSensitiveValues() throws Exception {
        UsageQueryService.UsageFilters filters = new UsageQueryService.UsageFilters(
                "task-a", "deepseek", "deepseek-chat");
        when(service.summarize(null, null, "provider_model", 1000, filters)).thenReturn(
                new UsageQueryService.UsageSummary(Instant.parse("2026-08-23T00:00:00Z"),
                        Instant.parse("2026-08-24T00:00:00Z"),
                        new UsageQueryService.UsageTotals(0, 0, 0, 0, 0), List.of()));

        MockMvcBuilders.standaloneSetup(new UsageController(service, audit)).build()
                .perform(get("/api/v1/usage/export")
                        .param("taskId", "task-a")
                        .param("provider", "deepseek")
                        .param("model", "deepseek-chat"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<AuditEvent> event = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().action()).isEqualTo("USAGE_EXPORTED");
        assertThat(event.getValue().resourceType()).isEqualTo("USAGE");
        assertThat(event.getValue().attributes()).containsEntry("taskId", "task-a")
                .containsEntry("provider", "deepseek").containsEntry("model", "deepseek-chat")
                .doesNotContainKey("prompt");
    }
}
