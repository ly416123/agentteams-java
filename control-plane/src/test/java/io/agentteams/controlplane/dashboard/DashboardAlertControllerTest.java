package io.agentteams.controlplane.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class DashboardAlertControllerTest {
    @Mock private UsageQueryService usage;
    @Mock private DashboardAlertService alerts;
    @Mock private DashboardAlertNotificationPort notifications;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DashboardAlertController(usage, alerts, notifications)).build();
    }

    @Test
    void explicitlyDeliversEvaluatedAlertsThroughTheNotificationPort() throws Exception {
        Instant from = Instant.parse("2026-08-25T00:00:00Z");
        Instant to = Instant.parse("2026-08-25T01:00:00Z");
        DashboardAlertService.Alert alert = new DashboardAlertService.Alert(
                "FAILURE_RATE", "CRITICAL", 0.5, "failure rate exceeded configured threshold");
        when(usage.summarize(from, to)).thenReturn(new UsageQueryService.UsageSummary(from, to,
                new UsageQueryService.UsageTotals(2, 1, 10, 5, 1), List.of()));
        when(alerts.evaluate(any())).thenReturn(List.of(alert));
        when(notifications.notify(any())).thenReturn(
                new DashboardAlertNotificationPort.NotificationResult("webhook", true, 1));

        mockMvc.perform(post("/api/v1/dashboard/alerts/notify")
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("webhook"))
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.alertCount").value(1));

        verify(notifications).notify(any());
    }
}
