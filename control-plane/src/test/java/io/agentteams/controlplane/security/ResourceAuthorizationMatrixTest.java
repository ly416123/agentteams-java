package io.agentteams.controlplane.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.agentteams.controlplane.project.ProjectRole;
import org.junit.jupiter.api.Test;

class ResourceAuthorizationMatrixTest {
    @Test
    void everyProjectRoleHasAnExplicitAllowedActionSet() {
        var expected = java.util.Map.of(
                ProjectRole.OWNER, java.util.EnumSet.allOf(ResourceAction.class),
                ProjectRole.ADMIN, java.util.EnumSet.of(ResourceAction.PROJECT_READ,
                        ResourceAction.PROJECT_MEMBER_INVITE, ResourceAction.PROJECT_MEMBER_DISABLE,
                        ResourceAction.PROJECT_MEMBER_ENABLE, ResourceAction.PROJECT_MEMBER_ROLE_CHANGE,
                        ResourceAction.TEAM_READ, ResourceAction.TEAM_WRITE, ResourceAction.TASK_READ,
                        ResourceAction.TASK_CREATE, ResourceAction.TASK_OPERATE, ResourceAction.TASK_APPROVE,
                        ResourceAction.TASK_CANCEL, ResourceAction.WORKER_OPERATE,
                        ResourceAction.ARTIFACT_READ, ResourceAction.ARTIFACT_WRITE),
                ProjectRole.OPERATOR, java.util.EnumSet.of(ResourceAction.PROJECT_READ,
                        ResourceAction.TEAM_READ, ResourceAction.TASK_READ, ResourceAction.TASK_OPERATE,
                        ResourceAction.TASK_CANCEL, ResourceAction.WORKER_OPERATE, ResourceAction.ARTIFACT_READ),
                ProjectRole.DEVELOPER, java.util.EnumSet.of(ResourceAction.PROJECT_READ,
                        ResourceAction.TEAM_READ, ResourceAction.TASK_READ, ResourceAction.TASK_CREATE,
                        ResourceAction.ARTIFACT_READ),
                ProjectRole.VIEWER, java.util.EnumSet.of(ResourceAction.PROJECT_READ,
                        ResourceAction.TEAM_READ, ResourceAction.TASK_READ, ResourceAction.ARTIFACT_READ));

        for (ProjectRole role : ProjectRole.values()) {
            for (ResourceAction action : ResourceAction.values()) {
                assertTrue(ResourceAuthorizationMatrix.allows(role, action) == expected.get(role).contains(action),
                        () -> role + " -> " + action);
            }
        }
    }

    @Test
    void onlyOwnerCanTransferOwnership() {
        assertTrue(ResourceAuthorizationMatrix.allows(ProjectRole.OWNER, ResourceAction.PROJECT_OWNER_TRANSFER));
        for (ProjectRole role : ProjectRole.values()) {
            if (role != ProjectRole.OWNER) {
                assertFalse(ResourceAuthorizationMatrix.allows(role, ResourceAction.PROJECT_OWNER_TRANSFER));
            }
        }
    }

    @Test
    void ownerAndAdminCanManageMembershipButOwnerTransferRequiresOwner() {
        for (ProjectRole role : new ProjectRole[] { ProjectRole.OWNER, ProjectRole.ADMIN }) {
            assertTrue(ResourceAuthorizationMatrix.allows(role, ResourceAction.PROJECT_MEMBER_INVITE));
            assertTrue(ResourceAuthorizationMatrix.allows(role, ResourceAction.PROJECT_MEMBER_DISABLE));
            assertTrue(ResourceAuthorizationMatrix.allows(role, ResourceAction.PROJECT_MEMBER_ENABLE));
            assertTrue(ResourceAuthorizationMatrix.allows(role, ResourceAction.PROJECT_MEMBER_ROLE_CHANGE));
        }
        assertFalse(ResourceAuthorizationMatrix.allows(ProjectRole.ADMIN, ResourceAction.PROJECT_OWNER_TRANSFER));
    }
}
