package io.agentteams.controlplane.memory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MemoryManagementControllerTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext(
            "org-a", "tenant-a", "project-a", "team-a", "user-a");

    private MemoryRepository memories;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        memories = mock(MemoryRepository.class);
        MemoryGovernanceService governance = mock(MemoryGovernanceService.class);
        ExecutionContextResolver contexts = mock(ExecutionContextResolver.class);
        when(contexts.resolve(org.mockito.ArgumentMatchers.any(Principal.class))).thenReturn(CONTEXT);
        mvc = MockMvcBuilders.standaloneSetup(new MemoryManagementController(memories, governance, contexts))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
        PrincipalContext.set(new Principal("user-a",
                new io.agentteams.controlplane.security.AuthorizationService.Scope(
                        "tenant-a", "project-a", "team-a"), Set.of("memory:read")));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void listDoesNotReturnMetadataFromAnotherProjectInTheSameTenant() throws Exception {
        MemoryRecord currentProject = memory("project-a");
        MemoryRecord otherProject = memory("project-b");
        when(memories.find(eq("org-a"), eq("tenant-a"), eq("project-a")))
                .thenReturn(List.of(currentProject, otherProject));

        mvc.perform(get("/api/v1/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].policy.projectId").value("project-a"));
    }

    private static MemoryRecord memory(String projectId) {
        MemoryPolicy policy = new MemoryPolicy(MemoryPolicy.Scope.PROJECT_SHARED, "org-a", "tenant-a", projectId,
                null, null, null, MemoryPolicy.Sensitivity.NORMAL, MemoryPolicy.Consent.CONFIRMED,
                Duration.ofHours(6));
        Instant now = Instant.parse("2026-08-31T00:00:00Z");
        return new MemoryRecord(UUID.randomUUID(), policy, "secret://memory/1", "summary", "conversation",
                now.plus(Duration.ofHours(6)), now, now, 0);
    }
}
