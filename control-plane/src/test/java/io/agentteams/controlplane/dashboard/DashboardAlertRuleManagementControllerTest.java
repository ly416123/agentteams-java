package io.agentteams.controlplane.dashboard;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.List;
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
class DashboardAlertRuleManagementControllerTest {
    @Mock
    private DashboardAlertRuleRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardAlertRuleManagementController(repository))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void listsRulesForTheAuthenticatedProjectScope() throws Exception {
        when(repository.findForScope("tenant-a", "project-a")).thenReturn(List.of(
                new DashboardAlertRule("COST", "WARNING", 25, true, 3)));

        mockMvc.perform(get("/api/v1/dashboard/alert-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rule").value("COST"))
                .andExpect(jsonPath("$[0].threshold").value(25))
                .andExpect(jsonPath("$[0].version").value(3));
        verify(repository).findForScope("tenant-a", "project-a");
    }

    @Test
    void updatesRuleWithAnOptimisticVersionGuard() throws Exception {
        DashboardAlertRule updated = new DashboardAlertRule("COST", "CRITICAL", 30, false, 4);
        when(repository.saveForScope(eq("tenant-a"), eq("project-a"), eq(
                new DashboardAlertRule("COST", "CRITICAL", 30, false)), eq(3L))).thenReturn(updated);

        mockMvc.perform(put("/api/v1/dashboard/alert-rules/COST")
                        .contentType("application/json")
                        .content("{\"severity\":\"CRITICAL\",\"threshold\":30,\"enabled\":false,\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("COST"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(4));
        verify(repository).saveForScope("tenant-a", "project-a",
                new DashboardAlertRule("COST", "CRITICAL", 30, false), 3);
    }
}
