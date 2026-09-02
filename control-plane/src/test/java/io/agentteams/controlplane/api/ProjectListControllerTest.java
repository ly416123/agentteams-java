package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.project.ProjectAuthorizationService;
import io.agentteams.controlplane.project.ProjectInvitationService;
import io.agentteams.controlplane.project.ProjectRole;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAction;
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
        when(service.list(any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CursorPage<>(List.of(project), null, false, now));

        mvc.perform(get("/api/v1/projects").param("pageSize", "20").param("q", "project")
                .param("search", "project").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("project-a"))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(service).list(any(), org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.eq("project"));
    }

    @Test
    void exposesAuthoritativeRolePermissionMatrix() throws Exception {
        UUID projectId = UUID.randomUUID();
        when(service.listRolePermissions(projectId)).thenReturn(List.of(
                new ProjectAuthorizationService.RolePermissions(ProjectRole.DEVELOPER,
                        List.of(ResourceAction.PROJECT_READ, ResourceAction.TASK_CREATE))));

        mvc.perform(get("/api/v1/projects/{projectId}/authorization/roles", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("DEVELOPER"))
                .andExpect(jsonPath("$[0].permissions[0]").value("PROJECT_READ"))
                .andExpect(jsonPath("$[0].permissions[1]").value("TASK_CREATE"));
    }

    @Test
    void requiresIdempotencyKeyForRoleChange() throws Exception {
        UUID projectId = UUID.randomUUID();

        mvc.perform(post("/api/v1/projects/{projectId}/members/{subject}/role", projectId, "developer")
                .header("Idempotency-Key", "role-key-1")
                .contentType(APPLICATION_JSON)
                .content("{\"role\":\"OPERATOR\",\"expectedMembershipVersion\":2}"))
                .andExpect(status().isNoContent());

        verify(service).changeRole(projectId, "role-key-1", "developer", ProjectRole.OPERATOR, 2);
    }
}
