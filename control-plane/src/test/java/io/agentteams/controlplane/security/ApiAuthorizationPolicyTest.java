package io.agentteams.controlplane.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiAuthorizationPolicyTest {
    @Test
    void highImpactLifecycleAndBudgetRoutesUseDedicatedPermissions() {
        assertPermission(Permission.AGENT_SPEC_PUBLISH, "/api/v1/agent-specs/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/publish");
        assertPermission(Permission.AGENT_SPEC_REVIEW,
                "/api/v1/worker-templates/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/revisions/4/review");
        assertPermission(Permission.AGENT_SPEC_INSTANTIATE,
                "/api/v1/worker-templates/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/revisions/4/instances");
        assertPermission(Permission.SKILL_REVIEW,
                "/api/v1/skills/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/versions/8c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/review");
        assertPermission(Permission.SKILL_PACKAGE_UPLOAD,
                "/api/v1/skills/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/versions/8c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/package/upload");
        assertPermission("GET", Permission.QUOTA_READ, "/api/v1/usage/budgets");
        assertPermission("PUT", Permission.QUOTA_WRITE,
                "/api/v1/usage/budgets/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5");
    }

    @Test
    void configurationExportAlertEvaluationAndRetentionMethodsMatchTheirSideEffects() {
        assertPermission("GET", Permission.CONFIG_READ, "/api/v1/config/agents/a/current");
        assertPermission("POST", Permission.CONFIG_WRITE, "/api/v1/config/snapshots");
        assertPermission("POST", Permission.CONFIG_WRITE, "/api/v1/config/snapshots/snapshot/uploads");
        assertPermission("GET", Permission.CONFIG_READ, "/api/v1/config/snapshots/snapshot/files/content");

        assertPermission("GET", Permission.USAGE_READ, "/api/v1/dashboard/alerts");
        assertPermission("POST", Permission.DASHBOARD_WRITE, "/api/v1/dashboard/alerts/notify");
        assertPermission("POST", Permission.DASHBOARD_WRITE,
                "/api/v1/dashboard/alerts/events/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/retry");
        assertPermission("POST", Permission.AGENT_SPEC_WRITE, "/api/v1/evaluations/runs");
        assertPermission("GET", Permission.AGENT_SPEC_READ,
                "/api/v1/evaluations/runs/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/report");

        assertPermission("GET", Permission.ARTIFACT_READ, "/api/v1/artifacts/retention");
        assertPermission("PUT", Permission.ARTIFACT_WRITE, "/api/v1/artifacts/retention");
        assertPermission("GET", Permission.MEMORY_READ, "/api/v1/memory");
        assertPermission("POST", Permission.MEMORY_GOVERN, "/api/v1/memory/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/governance");
        assertPermission("GET", Permission.SANDBOX_READ, "/api/v1/sandboxes");
        assertTrue(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/sandboxes")).isEmpty());
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(
                request("POST", "/api/v1/sandboxes")));
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(
                request("DELETE", "/api/v1/usage/summary")));
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(
                request("POST", "/api/v1/dashboard/summary")));
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(
                request("POST", "/api/v1/artifact-quality/events")));
    }

    @Test
    void managementRoutesDeferAuthorizationToDatabaseBackedService() {
        assertTrue(ApiAuthorizationPolicy.requiredPermission(request("GET", "/api/v1/management/users")).isEmpty());
        assertTrue(ApiAuthorizationPolicy.requiredPermission(request("POST", "/api/v1/management/organizations")).isEmpty());
        assertTrue(ApiAuthorizationPolicy.requiredPermission(request("POST",
                "/api/v1/management/credentials/123/rotate")).isEmpty());
    }

    @Test
    void managementTaskRoutesKeepTheirProjectTaskPermissionMapping() {
        org.junit.jupiter.api.Assertions.assertEquals(Permission.TASK_CANCEL,
                ApiAuthorizationPolicy.requiredPermission(request("POST",
                        "/api/v1/management/tasks/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/cancel")).orElseThrow());
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(
                request("GET", "/api/v1/management/tasks")));
    }

    @Test
    void projectRoutesRemainUnderDatabaseMembershipAuthorization() {
        var project = "4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5";
        for (var route : java.util.List.of(
                request("GET", "/api/v1/projects"),
                request("POST", "/api/v1/projects"),
                request("GET", "/api/v1/projects/" + project + "/members"),
                request("POST", "/api/v1/projects/" + project + "/members"),
                request("GET", "/api/v1/projects/" + project + "/authorization/roles"),
                request("GET", "/api/v1/projects/" + project + "/role"),
                request("POST", "/api/v1/projects/" + project + "/invitations"),
                request("POST", "/api/v1/projects/" + project + "/invitations/accept"),
                request("POST", "/api/v1/projects/" + project + "/owner/transfer"),
                request("POST", "/api/v1/projects/" + project + "/members/alice/enable"),
                request("POST", "/api/v1/projects/" + project + "/members/alice/role"),
                request("DELETE", "/api/v1/projects/" + project + "/members/alice"))) {
            assertTrue(ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(route),
                    route.getMethod() + " " + route.getRequestURI());
            assertTrue(ApiAuthorizationPolicy.requiredPermission(route).isEmpty(),
                    "project membership service owns authorization for " + route.getMethod() + " "
                            + route.getRequestURI());
        }
    }


    @Test
    void allKnownUserApiRootsHaveAnExplicitPermissionOrServiceAuthorizationBoundary() {
        for (var route : java.util.List.of(
                request("GET", "/api/v1/projects"),
                request("GET", "/api/v1/teams"),
                request("POST", "/api/v1/teams"),
                request("GET", "/api/v1/agents"),
                request("GET", "/api/v1/tasks"),
                request("GET", "/api/v1/tasks/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/runs"),
                request("GET", "/api/v1/artifacts"),
                request("GET", "/api/v1/config/agents/a/current"),
                request("GET", "/api/v1/model-providers"),
                request("GET", "/api/v1/model-prices"),
                request("GET", "/api/v1/agent-specs"),
                request("GET", "/api/v1/skills"),
                request("GET", "/api/v1/mcp-servers"),
                request("GET", "/api/v1/memory"),
                request("GET", "/api/v1/sandboxes"),
                request("GET", "/api/v1/usage/summary"),
                request("GET", "/api/v1/usage/quota"),
                request("GET", "/api/v1/dashboard/alerts"),
                request("GET", "/api/v1/audit-events"),
                request("POST", "/api/v1/projects/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/owner/transfer"),
                request("POST", "/api/v1/management/organizations"),
                request("POST", "/api/v1/management/authorizations/platform/admins/root/revoke"),
                request("GET", "/api/v1/artifact-quality/events"),
                request("POST", "/api/v1/commands"),
                request("GET", "/api/v1/evaluations/runs"),
                request("GET", "/api/v1/matrix/rooms/bindings"),
                request("GET", "/api/v1/mcp/connections"),
                request("GET", "/api/v1/scheduled-tasks"),
                request("POST", "/api/v1/webhooks"),
                request("GET", "/api/v1/worker-templates"))) {
            org.junit.jupiter.api.Assertions.assertTrue(ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(route),
                    route.getMethod() + " " + route.getRequestURI());
        }
    }

    @Test
    void unknownApiRoutesDefaultDeny() {
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(request("GET", "/api/v1/unclassified")));
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(request("POST", "/api/v1/projects/4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5/unknown")));
        assertTrue(!ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(request("POST", "/api/v1/management/unknown")));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private static void assertPermission(Permission expected, String path) {
        assertPermission("POST", expected, path);
    }

    private static void assertPermission(String method, Permission expected, String path) {
        org.junit.jupiter.api.Assertions.assertEquals(expected,
                ApiAuthorizationPolicy.requiredPermission(request(method, path)).orElseThrow(), path);
    }
}
