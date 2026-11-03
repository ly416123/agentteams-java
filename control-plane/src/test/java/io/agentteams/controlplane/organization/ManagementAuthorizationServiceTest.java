package io.agentteams.controlplane.organization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ProjectScopeResolver;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ManagementAuthorizationServiceTest {
    private final ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
    private final ManagementAuthorizationService service = new ManagementAuthorizationService(roles);
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @AfterEach
    void clearPrincipal() { PrincipalContext.clear(); }

    @Test
    void platformAdministratorCanManageAnyOrganization() {
        PrincipalContext.set(principal("root"));
        when(roles.hasRole("root", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(true);
        assertThatCode(() -> service.requireOrganizationAdmin(orgB)).doesNotThrowAnyException();
    }

    @Test
    void organizationAdministratorCannotCrossOrganizationBoundary() {
        PrincipalContext.set(principal("org-admin"));
        when(roles.hasRole("org-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-admin", "ORGANIZATION", orgA, "ORGANIZATION_ADMIN")).thenReturn(true);
        assertThatCode(() -> service.requireOrganizationAdmin(orgA)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireOrganizationAdmin(orgB)).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void tokenClaimsAloneDoNotGrantManagementRole() {
        PrincipalContext.set(new Principal("member", scope(), Set.of("organization:write", "platform:organization:create")));
        when(roles.hasRole("member", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("member", "ORGANIZATION", orgA, "ORGANIZATION_ADMIN")).thenReturn(false);
        assertThatThrownBy(() -> service.requireOrganizationAdmin(orgA)).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void effectivePermissionsAreDerivedFromPersistedManagementAssignments() {
        String tenantId = "00000000-0000-0000-0000-00000000000a";
        PrincipalContext.set(new Principal("org-admin", new AuthorizationService.Scope(tenantId, "p", "team"),
                Set.of()));
        when(roles.list("org-admin")).thenReturn(java.util.List.of(
                new ManagementRoleRepository.ManagementRoleAssignment(UUID.randomUUID(), "org-admin",
                        "ORGANIZATION", orgA, "ORGANIZATION_ADMIN", "ACTIVE", 0)));
        var permissions = service.effectivePermissions().permissions();
        org.assertj.core.api.Assertions.assertThat(permissions).contains("integration:read", "credential:rotate",
                "integration:write", "credential:read", "credential:manage", "organization:admin",
                "provisioning-policy:manage", "external-user:manage", "identity-mapping:read",
                "identity-mapping:write", "identity:read")
                .doesNotContain("admin:user:write", "user:manage", "platform:organization:read", "project:read");
    }



    @Test
    void assignmentListingIsRestrictedToPlatformAdministrators() {
        PrincipalContext.set(principal("root"));
        when(roles.hasRole("root", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(true);
        var assignment = new ManagementRoleRepository.ManagementRoleAssignment(UUID.randomUUID(), "org-admin",
                "ORGANIZATION", orgA, "ORGANIZATION_ADMIN", "ACTIVE", 2);
        when(roles.listByScope("ORGANIZATION", orgA)).thenReturn(java.util.List.of(assignment));
        org.assertj.core.api.Assertions.assertThat(service.assignments("organization", orgA)).containsExactly(assignment);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assignments("PLATFORM", orgA))
                .isInstanceOf(IllegalArgumentException.class);

        PrincipalContext.set(principal("not-root"));
        when(roles.hasRole("not-root", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assignments("PLATFORM", null))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void platformAdministratorCanCreateOrganizationsInPermissionSummary() {
        PrincipalContext.set(principal("root"));
        when(roles.list("root")).thenReturn(java.util.List.of(
                new ManagementRoleRepository.ManagementRoleAssignment(UUID.randomUUID(), "root",
                        "PLATFORM", null, "PLATFORM_ADMIN", "ACTIVE", 0)));
        org.assertj.core.api.Assertions.assertThat(service.effectivePermissions().permissions())
                .contains("platform:organization:create", "project:create", "admin:user:write",
                        "platform:organization:read", "user:manage", "organization:admin", "integration:read",
                        "integration:write", "provisioning-policy:manage", "external-user:manage",
                        "identity-mapping:read", "identity-mapping:write", "management:role:manage");
    }


    @Test
    void globalProjectSettingsPermissionRequiresAtLeastOnePersistedMembership() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        PrincipalContext.set(new Principal("member", scope(), Set.of()));
        when(roles.list("member")).thenReturn(java.util.List.of());
        when(projectRepository.findProjects("t", "member", null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null))
                .thenReturn(java.util.List.of(ProjectRecord.create(UUID.randomUUID(), "t", "project", "owner",
                        Instant.EPOCH)));
        var auth = new ManagementAuthorizationService(roles, null, projectRepository, event -> { },
                java.time.Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThat(auth.effectivePermissions().permissions()).contains("project:read");

        when(projectRepository.findProjects("t", "member", null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null)).thenReturn(java.util.List.of());
        org.assertj.core.api.Assertions.assertThat(auth.effectivePermissions().permissions())
                .doesNotContain("project:read");
    }

    @Test
    void organizationAdministratorCanReadProjectsOnlyInTheirTenant() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        OrganizationManagementRepository organizationRepository = mock(OrganizationManagementRepository.class);
        String tenantId = "00000000-0000-0000-0000-00000000000a";
        PrincipalContext.set(new Principal("org-admin", new AuthorizationService.Scope(tenantId, "p", "team"),
                Set.of()));
        when(roles.list("org-admin")).thenReturn(java.util.List.of());
        when(roles.hasRole("org-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-admin", "ORGANIZATION", orgA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(organizationRepository.organizationIdForTenantReference(tenantId))
                .thenReturn(java.util.Optional.of(orgA));
        when(projectRepository.findProjectsByTenant(tenantId, null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null))
                .thenReturn(java.util.List.of(ProjectRecord.create(UUID.randomUUID(),
                        tenantId, "org-project", "owner", Instant.EPOCH)));
        var auth = new ManagementAuthorizationService(roles, null, projectRepository, organizationRepository,
                event -> { });
        org.assertj.core.api.Assertions.assertThat(auth.effectivePermissions().permissions()).contains("project:read");
        verify(projectRepository).findProjectsByTenant(tenantId, null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null);
    }

    @Test
    void tenantExternalKeyDoesNotCauseBadRequestWhenCheckingTenantProjectAccess() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        OrganizationManagementRepository organizationRepository = mock(OrganizationManagementRepository.class);
        String tenantId = "tenant-a";
        PrincipalContext.set(new Principal("member", new AuthorizationService.Scope("tenant-a", "project-a", "team"),
                Set.of()));
        when(roles.list("member")).thenReturn(java.util.List.of());
        when(roles.hasRole("member", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(organizationRepository.organizationIdForTenantReference("tenant-a"))
                .thenReturn(java.util.Optional.of(orgA));
        when(roles.hasRole("member", "ORGANIZATION", orgA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(projectRepository.findProjectsByTenant(tenantId, null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null))
                .thenReturn(java.util.List.of(ProjectRecord.create(UUID.randomUUID(), tenantId,
                        "org-project", "owner", Instant.EPOCH)));
        var auth = new ManagementAuthorizationService(roles, null, projectRepository, organizationRepository,
                event -> { });

        org.assertj.core.api.Assertions.assertThat(auth.effectivePermissions().permissions()).contains("project:read");
        verify(projectRepository).findProjectsByTenant(tenantId, null, 1,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null);
    }

    @Test
    void projectAdminPermissionsComeFromMembershipRoleInCanonicalProjectScope() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        UUID projectId = UUID.randomUUID();
        PrincipalContext.set(new Principal("project-admin", new AuthorizationService.Scope("t",
                projectId.toString(), "team"), Set.of()));
        when(roles.list("project-admin")).thenReturn(java.util.List.of());
        when(projectRepository.findProject("t", projectId)).thenReturn(java.util.Optional.of(
                ProjectRecord.create(projectId, "t", "project", "creator", Instant.EPOCH)));
        when(projectRepository.findMembership("t", projectId, "project-admin")).thenReturn(java.util.Optional.of(
                ProjectMembershipRecord.create("t", projectId, "project-admin", ProjectRole.ADMIN, Instant.EPOCH)));
        var projectAuthorization = new ManagementAuthorizationService(roles,
                new ProjectScopeResolver(projectRepository), projectRepository, event -> { }, java.time.Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThat(projectAuthorization.effectivePermissions(projectId.toString()).permissions())
                .contains("model:write", "mcp:write", "task:create", "project:read");
    }


    @Test
    void platformAdministratorCanRevokeOrganizationAdminWithVersionAndAudit() {
        PrincipalContext.set(principal("root"));
        when(roles.hasRole("root", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(true);
        when(roles.updateStatus("org-admin", "ORGANIZATION", orgA, "DISABLED", 4L, "root",
                java.time.Instant.EPOCH)).thenReturn(true);
        var audit = mock(io.agentteams.controlplane.audit.AuditRecorder.class);
        var tested = new ManagementAuthorizationService(roles, null, null, audit, java.time.Clock.fixed(
                java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
        assertThatCode(() -> tested.revokeOrganizationAdmin(orgA, "org-admin", 4L)).doesNotThrowAnyException();
        verify(roles).updateStatus("org-admin", "ORGANIZATION", orgA, "DISABLED", 4L, "root",
                java.time.Instant.EPOCH);
        verify(audit).record(org.mockito.ArgumentMatchers.argThat((io.agentteams.controlplane.audit.AuditEvent event) ->
                event.action().equals("ORGANIZATION_ADMIN_REVOKED")));
    }

    @Test
    void platformAdministratorCannotRevokeSelfAndStaleRoleVersionConflicts() {
        PrincipalContext.set(principal("root"));
        when(roles.hasRole("root", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(true);
        assertThatThrownBy(() -> service.revokePlatformAdmin("root", 0L))
                .isInstanceOf(AuthorizationException.class);
        when(roles.updateStatus("other", "PLATFORM", null, "DISABLED", 9L, "root", java.time.Instant.EPOCH))
                .thenReturn(false);
        assertThatThrownBy(() -> service.revokePlatformAdmin("other", 9L))
                .isInstanceOf(ManagementRoleConflictException.class);
    }

    @Test
    void effectiveProjectPermissionsMatchTheProjectApiRoleThresholds() {
        assertProjectPermissions(ProjectRole.VIEWER, false, false, false);
        assertProjectPermissions(ProjectRole.DEVELOPER, true, false, false);
        assertProjectPermissions(ProjectRole.OPERATOR, true, true, false);
        assertProjectPermissions(ProjectRole.ADMIN, true, true, true);
        assertProjectPermissions(ProjectRole.OWNER, true, true, true);
    }

    private void assertProjectPermissions(ProjectRole role, boolean createsTasks, boolean operatesTasks,
            boolean administers) {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        UUID projectId = UUID.randomUUID();
        String subject = "member-" + role;
        PrincipalContext.set(new Principal(subject, new AuthorizationService.Scope("t", projectId.toString(), "team"),
                Set.of()));
        when(roles.list(subject)).thenReturn(java.util.List.of());
        when(projectRepository.findProject("t", projectId)).thenReturn(java.util.Optional.of(
                ProjectRecord.create(projectId, "t", "project", "creator", Instant.EPOCH)));
        when(projectRepository.findMembership("t", projectId, subject)).thenReturn(java.util.Optional.of(
                ProjectMembershipRecord.create("t", projectId, subject, role, Instant.EPOCH)));
        var auth = new ManagementAuthorizationService(roles, new ProjectScopeResolver(projectRepository),
                projectRepository, event -> { }, java.time.Clock.systemUTC());
        var permissions = auth.effectivePermissions(projectId.toString()).permissions();
        org.assertj.core.api.Assertions.assertThat(permissions).contains("agent:read", "model:read", "sandbox:read");
        org.assertj.core.api.Assertions.assertThat(permissions.contains("task:create")).isEqualTo(createsTasks);
        org.assertj.core.api.Assertions.assertThat(permissions.contains("task:cancel")).isEqualTo(operatesTasks);
        org.assertj.core.api.Assertions.assertThat(permissions.contains("model:write")).isEqualTo(administers);
        org.assertj.core.api.Assertions.assertThat(permissions.contains("audit:read")).isEqualTo(administers);
        org.assertj.core.api.Assertions.assertThat(permissions.contains("project:member:manage")).isEqualTo(administers);
    }

    private static Principal principal(String subject) {
        return new Principal(subject, scope(), Set.of("organization:write"));
    }
    private static AuthorizationService.Scope scope() { return new AuthorizationService.Scope("t", "p", "team"); }
}
