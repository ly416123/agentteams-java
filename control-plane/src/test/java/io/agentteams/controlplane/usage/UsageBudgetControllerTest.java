package io.agentteams.controlplane.usage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class UsageBudgetControllerTest {
    private static final AuthorizationService.Scope SCOPE =
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a");
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Mock
    private UsageBudgetService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice", SCOPE, Set.of("usage:read", "quota:write")));
        mockMvc = MockMvcBuilders.standaloneSetup(new UsageBudgetController(service)).build();
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void putsPolicyUsingCallerScopeAndExpectedVersion() throws Exception {
        UUID id = UUID.randomUUID();
        UsageBudgetPolicy policy = policy(id);
        when(service.upsert(eq(id), eq(SCOPE), any(UsageBudgetService.PolicyInput.class))).thenReturn(policy);

        mockMvc.perform(put("/api/v1/usage/budgets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency":"USD","periodSeconds":86400,"softThreshold":10,
                                 "hardThreshold":20,"forecastWindowSeconds":3600,"status":"ACTIVE",
                                 "expectedVersion":0,"tenantId":"attacker","projectId":"other"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.projectId").value("project-a"))
                .andExpect(jsonPath("$.version").value(0));
        verify(service).upsert(eq(id), eq(SCOPE), eq(new UsageBudgetService.PolicyInput(
                "USD", Duration.ofSeconds(86400), new BigDecimal("10"), new BigDecimal("20"),
                Duration.ofSeconds(3600), UsageBudgetPolicy.Status.ACTIVE, 0)));
    }

    @Test
    void listsOnlyPoliciesInCallerScope() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list(SCOPE)).thenReturn(List.of(policy(id)));

        mockMvc.perform(get("/api/v1/usage/budgets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].currency").value("USD"));
        verify(service).list(SCOPE);
    }

    @Test
    void returnsScopedEvaluationsAndValidatesLimit() throws Exception {
        UUID id = UUID.randomUUID();
        UsageBudgetEvaluation evaluation = new UsageBudgetEvaluation(UUID.randomUUID(), id,
                NOW.minus(Duration.ofHours(24)), NOW, new BigDecimal("6"), new BigDecimal("72"),
                UsageBudgetEvaluation.Status.HARD_LIMIT, NOW);
        when(service.evaluations(id, SCOPE, 20)).thenReturn(List.of(evaluation));

        mockMvc.perform(get("/api/v1/usage/budgets/{id}/evaluations", id).param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("HARD_LIMIT"))
                .andExpect(jsonPath("$[0].forecastCost").value(72));
        verify(service).evaluations(id, SCOPE, 20);

        mockMvc.perform(get("/api/v1/usage/budgets/{id}/evaluations", id).param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    private static UsageBudgetPolicy policy(UUID id) {
        return new UsageBudgetPolicy(id, "tenant-a", "project-a", "USD", Duration.ofDays(1),
                new BigDecimal("10"), new BigDecimal("20"), Duration.ofHours(1), UsageBudgetPolicy.Status.ACTIVE,
                NOW, NOW, 0);
    }
}
