package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import org.springframework.mock.web.MockFilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthenticationFilterTest {
    private static final String TASK_MCP_TOKEN = "task-mcp-test-token";
    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SUBTASK_ID = "22222222-2222-2222-2222-222222222222";

    @Test
    void rejectsApiRequestsWithoutBearerToken() throws Exception {
        IdentityTokenValidator validator = token -> Optional.empty();
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void requiresOidcAuthenticationForEveryManagementControllerRoute() throws Exception {
        String organization = "11111111-1111-1111-1111-111111111111";
        String integration = "22222222-2222-2222-2222-222222222222";
        String credential = "33333333-3333-3333-3333-333333333333";
        String user = "44444444-4444-4444-4444-444444444444";
        String prefix = "/api/v1/management";
        var routes = java.util.List.<String[]>of(
                new String[] {"GET", prefix + "/organizations"},
                new String[] {"GET", prefix + "/organizations/" + organization + "/tenants"},
                new String[] {"GET", prefix + "/organizations/" + organization + "/integrations"},
                new String[] {"GET", prefix + "/organizations/" + organization + "/memberships"},
                new String[] {"POST", prefix + "/organizations"},
                new String[] {"POST", prefix + "/organizations/" + organization + "/tenants"},
                new String[] {"POST", prefix + "/organizations/" + organization + "/status"},
                new String[] {"POST", prefix + "/tenants/" + organization + "/status"},
                new String[] {"POST", prefix + "/organizations/" + organization + "/integrations"},
                new String[] {"GET", prefix + "/integrations/" + integration + "/credentials"},
                new String[] {"POST", prefix + "/integrations/" + integration + "/credentials"},
                new String[] {"POST", prefix + "/credentials/" + credential + "/rotate"},
                new String[] {"POST", prefix + "/credentials/" + credential + "/revoke"},
                new String[] {"PUT", prefix + "/integrations/" + integration + "/provisioning-policy"},
                new String[] {"POST", prefix + "/users"},
                new String[] {"GET", prefix + "/users"},
                new String[] {"POST", prefix + "/users/" + user + "/status"},
                new String[] {"POST", prefix + "/organizations/" + organization + "/memberships"},
                new String[] {"GET", prefix + "/integrations/" + integration + "/external-identities"},
                new String[] {"POST", prefix + "/integrations/" + integration + "/external-identities"},
                new String[] {"POST", prefix + "/integrations/" + integration + "/provisioned-users"},
                new String[] {"PUT", prefix + "/integrations/" + integration + "/provisioned-users/external-1"},
                new String[] {"POST", prefix + "/integrations/" + integration + "/provisioned-users/external-1/disable"},
                new String[] {"GET", prefix + "/integrations/" + integration + "/provisioned-users/external-1/memberships"},
                new String[] {"GET", prefix + "/authorizations/assignments"},
                new String[] {"GET", prefix + "/authorizations/me"},
                new String[] {"GET", prefix + "/authorizations/permissions"},
                new String[] {"POST", prefix + "/authorizations/organizations/" + organization + "/admins"},
                new String[] {"POST", prefix + "/authorizations/platform/admins"},
                new String[] {"POST", prefix + "/authorizations/organizations/" + organization + "/admins/user-1/revoke"},
                new String[] {"POST", prefix + "/authorizations/platform/admins/user-1/revoke"});
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(token -> Optional.empty());

        for (String[] route : routes) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(route[0], route[1]), response, new MockFilterChain());
            assertThat(response.getStatus()).as("%s %s", route[0], route[1]).isEqualTo(401);
        }
    }

    @Test
    void requiresOidcAuthenticationForEveryProjectControllerRoute() throws Exception {
        String project = "55555555-5555-5555-5555-555555555555";
        String prefix = "/api/v1/projects";
        var routes = java.util.List.<String[]>of(
                new String[] {"POST", prefix},
                new String[] {"POST", prefix + "/" + project + "/members"},
                new String[] {"POST", prefix + "/" + project + "/invitations"},
                new String[] {"POST", prefix + "/" + project + "/invitations/accept"},
                new String[] {"POST", prefix + "/" + project + "/owner/transfer"},
                new String[] {"POST", prefix + "/" + project + "/members/alice/enable"},
                new String[] {"POST", prefix + "/" + project + "/members/alice/role"},
                new String[] {"GET", prefix},
                new String[] {"GET", prefix + "/" + project + "/members"},
                new String[] {"GET", prefix + "/" + project + "/authorization/roles"},
                new String[] {"DELETE", prefix + "/" + project + "/members/alice"},
                new String[] {"GET", prefix + "/" + project + "/role"});
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(token -> Optional.empty());

        for (String[] route : routes) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(route[0], route[1]), response, new MockFilterChain());
            assertThat(response.getStatus()).as("%s %s", route[0], route[1]).isEqualTo(401);
        }
    }

    @Test
    void acceptsTaskMcpTokenOnlyOnSubtaskAndOutputUploadRoutes() throws Exception {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(token -> Optional.empty(), null,
                TASK_MCP_TOKEN);
        MockHttpServletRequest plan = new MockHttpServletRequest("PUT",
                "/api/v1/tasks/" + TASK_ID + "/subtasks");
        plan.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse planResponse = new MockHttpServletResponse();

        filter.doFilter(plan, planResponse, new MockFilterChain());

        assertThat(planResponse.getStatus()).isEqualTo(200);
        assertThat(PrincipalContext.current()).isEmpty();

        MockHttpServletRequest status = new MockHttpServletRequest("PUT",
                "/api/v1/tasks/" + TASK_ID + "/subtasks/" + SUBTASK_ID + "/status");
        status.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse statusResponse = new MockHttpServletResponse();

        filter.doFilter(status, statusResponse, new MockFilterChain());

        assertThat(statusResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest outputUpload = new MockHttpServletRequest("POST",
                "/api/v1/tasks/" + TASK_ID + "/files");
        outputUpload.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse uploadResponse = new MockHttpServletResponse();

        filter.doFilter(outputUpload, uploadResponse, (request, response) -> assertThat(
                request.getAttribute(ApiAuthenticationFilter.TASK_MCP_REQUEST_ATTRIBUTE)).isEqualTo(Boolean.TRUE));

        assertThat(uploadResponse.getStatus()).isEqualTo(200);
        assertThat(PrincipalContext.current()).isEmpty();

        MockHttpServletRequest sourceCapture = new MockHttpServletRequest("POST",
                "/api/v1/tasks/" + TASK_ID + "/source-evidence");
        sourceCapture.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse sourceCaptureResponse = new MockHttpServletResponse();

        filter.doFilter(sourceCapture, sourceCaptureResponse, new MockFilterChain());

        assertThat(sourceCaptureResponse.getStatus()).isEqualTo(200);
        assertThat(PrincipalContext.current()).isEmpty();
    }

    @Test
    void rejectsTaskMcpTokenOnOtherRoutesOrWithWrongValue() throws Exception {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(token -> Optional.empty(), null,
                TASK_MCP_TOKEN);
        MockHttpServletRequest wrongRoute = new MockHttpServletRequest("GET", "/api/v1/tasks/" + TASK_ID);
        wrongRoute.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse wrongRouteResponse = new MockHttpServletResponse();

        filter.doFilter(wrongRoute, wrongRouteResponse, new MockFilterChain());

        assertThat(wrongRouteResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest fileList = new MockHttpServletRequest("GET",
                "/api/v1/tasks/" + TASK_ID + "/files");
        fileList.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse fileListResponse = new MockHttpServletResponse();

        filter.doFilter(fileList, fileListResponse, new MockFilterChain());

        assertThat(fileListResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest attachmentUpload = new MockHttpServletRequest("POST",
                "/api/v1/tasks/" + TASK_ID + "/attachments");
        attachmentUpload.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse attachmentResponse = new MockHttpServletResponse();

        filter.doFilter(attachmentUpload, attachmentResponse, new MockFilterChain());

        assertThat(attachmentResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest wrongToken = new MockHttpServletRequest("GET",
                "/api/v1/tasks/" + TASK_ID + "/subtasks");
        wrongToken.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse wrongTokenResponse = new MockHttpServletResponse();

        filter.doFilter(wrongToken, wrongTokenResponse, new MockFilterChain());

        assertThat(wrongTokenResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest sourceRead = new MockHttpServletRequest("GET",
                "/api/v1/tasks/" + TASK_ID + "/source-evidence");
        sourceRead.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse sourceReadResponse = new MockHttpServletResponse();
        filter.doFilter(sourceRead, sourceReadResponse, new MockFilterChain());
        assertThat(sourceReadResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest malformedSourceCapture = new MockHttpServletRequest("POST",
                "/api/v1/tasks/not-a-uuid/source-evidence");
        malformedSourceCapture.addHeader("Authorization", "Bearer " + TASK_MCP_TOKEN);
        MockHttpServletResponse malformedCaptureResponse = new MockHttpServletResponse();
        filter.doFilter(malformedSourceCapture, malformedCaptureResponse, new MockFilterChain());
        assertThat(malformedCaptureResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void exposesAndCleansAuthenticatedPrincipal() throws Exception {
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project", "team"), Set.of("task:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> {
            assertThat(req.getAttribute(ApiAuthenticationFilter.PRINCIPAL_ATTRIBUTE)).isNotNull();
            assertThat(PrincipalContext.current()).isPresent();
            assertThat(PrincipalContext.actorOr("fallback")).isEqualTo("alice");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(PrincipalContext.current()).isEmpty();
    }

    @Test
    void rejectsAuthenticatedRequestsWithoutRoutePermission() throws Exception {
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project", "team"), Set.of("task:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    void canonicalizesAuthenticatedProjectNameAtApiBoundary() throws Exception {
        UUID id = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(id, "tenant", "project", "alice", Instant.EPOCH);
        when(projects.findProjectByName("tenant", "project")).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant", id, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant", id, "alice", ProjectRole.DEVELOPER, Instant.EPOCH)));
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project", "team"), Set.of("task:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator, new ProjectScopeResolver(projects));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setParameter("projectId", "project");
        request.addHeader("Authorization", "Bearer test-token");
        FilterChain chain = (req, ignored) -> assertThat(PrincipalContext.current().orElseThrow().scope().project())
                .isEqualTo(id.toString());

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void canonicalizesTokenProjectNameForConfigApiWithoutProjectQueryParameter() throws Exception {
        UUID id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(id, "tenant", "project-a", "alice", Instant.EPOCH);
        when(projects.findProjectByName("tenant", "project-a")).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant", id, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant", id, "alice", ProjectRole.DEVELOPER, Instant.EPOCH)));
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project-a", "team-a"),
                Set.of("config:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator, new ProjectScopeResolver(projects));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/agents/worker-id/current");
        request.addHeader("Authorization", "Bearer test-token");
        FilterChain chain = (req, ignored) -> assertThat(PrincipalContext.current().orElseThrow().scope().project())
                .isEqualTo(id.toString());

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void canonicalizesTokenProjectNameForTaskCreationAndUsesPersistedRole() throws Exception {
        UUID id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        ProjectRepository projects = mock(ProjectRepository.class);
        ProjectRecord project = ProjectRecord.create(id, "tenant", "project-a", "alice", Instant.EPOCH);
        when(projects.findProjectByName("tenant", "project-a")).thenReturn(Optional.of(project));
        when(projects.findProject("tenant", id)).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant", id, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant", id, "alice", ProjectRole.DEVELOPER, Instant.EPOCH)));
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project-a", "team-a"),
                Set.of("task:create")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator,
                new ProjectScopeResolver(projects), "", new ProjectApiAuthorization(projects));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> {
            Principal principal = PrincipalContext.current().orElseThrow();
            assertThat(principal.scope().project()).isEqualTo(id.toString());
            assertThat(principal.permissions()).contains("task:create");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void leavesTenantLevelRequestsUnresolvedWhenProjectIsNotRequested() throws Exception {
        ProjectRepository projects = mock(ProjectRepository.class);
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "quota-admin", new AuthorizationService.Scope("tenant", "project", "team"), Set.of("team:read")));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator, new ProjectScopeResolver(projects));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader("Authorization", "Bearer test-token");
        FilterChain chain = (req, ignored) -> assertThat(PrincipalContext.current().orElseThrow().scope().project())
                .isEqualTo("project");

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }


    @Test
    void injectsPersistedProjectRolePermissionsForServiceLayerChecks() throws Exception {
        UUID projectId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findProject("tenant", projectId)).thenReturn(Optional.of(
                ProjectRecord.create(projectId, "tenant", "project", "owner", Instant.EPOCH)));
        when(projects.findMembership("tenant", projectId, "viewer")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant", projectId, "viewer", ProjectRole.VIEWER, Instant.EPOCH)));
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "viewer", new AuthorizationService.Scope("tenant", projectId.toString(), "team"), Set.of()));
        var projectAuthorization = new ProjectApiAuthorization(projects);
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator, new ProjectScopeResolver(projects),
                "", projectAuthorization);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> assertThat(PrincipalContext.current().orElseThrow().permissions())
                .contains("task:read").doesNotContain("task:create");

        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void deniesUnclassifiedApiRoutesByDefault() throws Exception {
        IdentityTokenValidator validator = token -> Optional.of(new IdentityTokenValidator.IdentityPrincipal(
                "alice", new AuthorizationService.Scope("tenant", "project", "team"), Set.of()));
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(validator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/not-classified");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN");
    }

    @Test
    void mapsTaskLifecycleAndArtifactRoutesToTheirSpecificPermissions() {
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/tasks/id/retry")))
                .contains(Permission.TASK_RETRY);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/tasks/id/pause")))
                .contains(Permission.TASK_PAUSE);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/tasks/id/approve")))
                .contains(Permission.TASK_APPROVE);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/tasks/id/reject")))
                .contains(Permission.TASK_REJECT);
        assertThat(ApiAuthorizationPolicy.requiredPermission(
                request("POST", "/api/v1/tasks/id/attempts/attempt/artifacts/uploads")))
                .contains(Permission.ARTIFACT_WRITE);
    }

    @Test
    void mapsTeamRoutesToTeamPermissions() {
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/teams")))
                .contains(Permission.TEAM_READ);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/teams/id/members")))
                .contains(Permission.TEAM_READ);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/teams")))
                .contains(Permission.TEAM_WRITE);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("DELETE", "/api/v1/teams/id")))
                .contains(Permission.TEAM_WRITE);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/dashboard/alerts/notify")))
                .contains(Permission.DASHBOARD_WRITE);
    }

    @Test
    void mapsUsageExportToAnIndependentPermission() {
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/usage/export")))
                .contains(Permission.USAGE_EXPORT);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/usage/summary")))
                .contains(Permission.USAGE_READ);
    }

    @Test
    void mapsArtifactRetentionReadAndWriteToSeparatePermissions() {
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/artifacts/retention")))
                .contains(Permission.ARTIFACT_READ);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("PUT", "/api/v1/artifacts/retention")))
                .contains(Permission.ARTIFACT_WRITE);
    }

    @Test
    void mapsMemoryAndSandboxManagementRoutesToDedicatedPermissions() {
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/memory")))
                .contains(Permission.MEMORY_READ);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/memory/id/governance")))
                .contains(Permission.MEMORY_GOVERN);
        assertThat(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/sandboxes")))
                .contains(Permission.SANDBOX_READ);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
