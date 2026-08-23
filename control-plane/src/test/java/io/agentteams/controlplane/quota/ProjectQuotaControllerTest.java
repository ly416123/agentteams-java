package io.agentteams.controlplane.quota;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProjectQuotaControllerTest {
    @Mock private ProjectQuotaService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectQuotaController(service)).build();
    }

    @Test
    void returnsUnconfiguredProjectAsUnlimited() throws Exception {
        when(service.get("tenant-a", "project-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/usage/quota")
                        .param("tenantId", "tenant-a").param("projectId", "project-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.remainingDailyTokens").value(-1));
    }

    @Test
    void configuresAndReturnsProjectPolicy() throws Exception {
        ProjectQuotaSnapshot snapshot = new ProjectQuotaSnapshot("tenant-a", "project-a", true,
                4, 100, 10000, 1, 10, 500, LocalDate.of(2026, 8, 23));
        when(service.putPolicy(new ProjectQuotaPolicy("tenant-a", "project-a", 4, 100, 10000)))
                .thenReturn(snapshot);

        mockMvc.perform(put("/api/v1/usage/quota")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","projectId":"project-a",
                                 "maxConcurrentCalls":4,"maxDailyCalls":100,"maxDailyTokens":10000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.currentConcurrentCalls").value(1))
                .andExpect(jsonPath("$.remainingDailyCalls").value(90));
        verify(service).putPolicy(new ProjectQuotaPolicy("tenant-a", "project-a", 4, 100, 10000));
    }
}
