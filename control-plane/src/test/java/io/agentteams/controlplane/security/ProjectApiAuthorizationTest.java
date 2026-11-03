package io.agentteams.controlplane.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectApiAuthorizationTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectApiAuthorization authorization = new ProjectApiAuthorization(projects);
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(projects.findProject("tenant-a", projectId)).thenReturn(Optional.of(
                ProjectRecord.create(projectId, "tenant-a", "Project A", "owner", Instant.EPOCH)));
    }

    @Test
    void viewerCanReadProjectResourcesWithoutTokenPermissionClaim() {
        when(projects.findMembership("tenant-a", projectId, "viewer")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", projectId, "viewer", ProjectRole.VIEWER, Instant.EPOCH)));
        assertThatCode(() -> authorization.require(Permission.MODEL_READ, principal("viewer")))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(authorization.withEffectivePermissions(principal("viewer")).permissions())
                .contains("model:read", "task:read", "quota:read")
                .doesNotContain("model:write", "memory:govern", "agent-spec:publish", "skill:review", "quota:write");
        assertThatThrownBy(() -> authorization.require(Permission.MODEL_WRITE, principal("viewer")))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void projectAdminReceivesDedicatedLifecycleActionsAndQuotaWrite() {
        when(projects.findMembership("tenant-a", projectId, "admin")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", projectId, "admin", ProjectRole.ADMIN, Instant.EPOCH)));
        var principal = principal("admin");
        assertThatCode(() -> authorization.require(Permission.AGENT_SPEC_PUBLISH, principal)).doesNotThrowAnyException();
        assertThatCode(() -> authorization.require(Permission.SKILL_PACKAGE_UPLOAD, principal)).doesNotThrowAnyException();
        assertThatCode(() -> authorization.require(Permission.QUOTA_WRITE, principal)).doesNotThrowAnyException();
    }

    @Test
    void developerCanCreateTasksButCannotOperateThem() {
        when(projects.findMembership("tenant-a", projectId, "developer")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", projectId, "developer", ProjectRole.DEVELOPER, Instant.EPOCH)));
        assertThatCode(() -> authorization.require(Permission.TASK_CREATE, principal("developer")))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(authorization.withEffectivePermissions(principal("developer")).permissions())
                .contains("task:create").doesNotContain("task:cancel", "model:write");
        assertThatThrownBy(() -> authorization.require(Permission.TASK_CANCEL, principal("developer")))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void operatorCanOperateTasksButCannotManageProjectResources() {
        when(projects.findMembership("tenant-a", projectId, "operator")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", projectId, "operator", ProjectRole.OPERATOR, Instant.EPOCH)));
        Principal operator = principal("operator");
        assertThatCode(() -> authorization.require(Permission.TASK_CREATE, operator)).doesNotThrowAnyException();
        assertThatCode(() -> authorization.require(Permission.TASK_CANCEL, operator)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorization.require(Permission.MODEL_WRITE, operator))
                .isInstanceOf(AuthorizationException.class);
        assertThatThrownBy(() -> authorization.require(Permission.QUOTA_WRITE, operator))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void ownerCanUseAllAdministrativeProjectPermissions() {
        when(projects.findMembership("tenant-a", projectId, "owner")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", projectId, "owner", ProjectRole.OWNER, Instant.EPOCH)));
        Principal owner = principal("owner");
        for (Permission permission : java.util.List.of(Permission.TASK_APPROVE, Permission.MODEL_WRITE,
                Permission.AGENT_SPEC_PUBLISH, Permission.SKILL_PACKAGE_UPLOAD, Permission.MEMORY_EXPORT,
                Permission.USAGE_EXPORT, Permission.QUOTA_WRITE, Permission.DASHBOARD_WRITE)) {
            assertThatCode(() -> authorization.require(permission, owner)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsNonCanonicalAndCrossTenantProjects() {
        assertThatThrownBy(() -> authorization.require(Permission.TASK_READ,
                new Principal("viewer", new AuthorizationService.Scope("tenant-a", "Project A", "team"), Set.of())))
                .isInstanceOf(AuthorizationException.class);
        Principal otherTenant = new Principal("viewer",
                new AuthorizationService.Scope("tenant-b", projectId.toString(), "team"), Set.of());
        assertThatThrownBy(() -> authorization.require(Permission.TASK_READ, otherTenant))
                .isInstanceOf(AuthorizationException.class);
    }

    private Principal principal(String subject) {
        return new Principal(subject, new AuthorizationService.Scope("tenant-a", projectId.toString(), "team"), Set.of());
    }
}
