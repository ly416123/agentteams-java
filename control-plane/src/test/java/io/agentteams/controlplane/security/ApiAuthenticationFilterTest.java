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
        request.addHeader("Authorization", "Bearer test-token");
        FilterChain chain = (req, ignored) -> assertThat(PrincipalContext.current().orElseThrow().scope().project())
                .isEqualTo(id.toString());

        filter.doFilter(request, new MockHttpServletResponse(), chain);
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
