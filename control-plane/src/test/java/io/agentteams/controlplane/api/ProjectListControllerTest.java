package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.project.ProjectAuthorizationService;
import io.agentteams.controlplane.project.ProjectInvitationService;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectListControllerTest {
    private MockMvc mvc;
    private ProjectAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = mock(ProjectAuthorizationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new io.agentteams.controlplane.project.ProjectController(
                service, mock(ProjectInvitationService.class))).setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("actor-a", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("project:read")));
    }

    @AfterEach
    void clearContext() { PrincipalContext.clear(); }

    @Test
    void listsProjectsAsCursorPageWithinAuthenticatedTenant() throws Exception {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        ProjectRecord project = new ProjectRecord(UUID.randomUUID(), "tenant-a", "project-a", "ACTIVE", "actor-a",
                now, now, 0);
        when(service.list(any())).thenReturn(new CursorPage<>(List.of(project), null, false, now));

        mvc.perform(get("/api/v1/projects").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("project-a"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }
}
