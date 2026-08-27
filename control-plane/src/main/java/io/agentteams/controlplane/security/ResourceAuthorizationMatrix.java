package io.agentteams.controlplane.security;

import io.agentteams.controlplane.project.ProjectRole;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Immutable project-role to action matrix; no identity claims can widen it. */
public final class ResourceAuthorizationMatrix {
    private static final Map<ProjectRole, Set<ResourceAction>> ALLOWED = Map.of(
            ProjectRole.OWNER, EnumSet.allOf(ResourceAction.class),
            ProjectRole.ADMIN, EnumSet.of(ResourceAction.PROJECT_READ, ResourceAction.PROJECT_MEMBER_INVITE,
                    ResourceAction.PROJECT_MEMBER_DISABLE,
                    ResourceAction.PROJECT_MEMBER_ENABLE, ResourceAction.PROJECT_MEMBER_ROLE_CHANGE,
                    ResourceAction.TEAM_READ, ResourceAction.TEAM_WRITE, ResourceAction.TASK_READ,
                    ResourceAction.TASK_CREATE, ResourceAction.TASK_OPERATE, ResourceAction.TASK_APPROVE,
                    ResourceAction.TASK_CANCEL, ResourceAction.WORKER_OPERATE),
            ProjectRole.OPERATOR, EnumSet.of(ResourceAction.PROJECT_READ, ResourceAction.TEAM_READ,
                    ResourceAction.TASK_READ, ResourceAction.TASK_OPERATE, ResourceAction.TASK_CANCEL,
                    ResourceAction.WORKER_OPERATE),
            ProjectRole.DEVELOPER, EnumSet.of(ResourceAction.PROJECT_READ, ResourceAction.TEAM_READ,
                    ResourceAction.TASK_READ, ResourceAction.TASK_CREATE),
            ProjectRole.VIEWER, EnumSet.of(ResourceAction.PROJECT_READ, ResourceAction.TEAM_READ,
                    ResourceAction.TASK_READ));

    private ResourceAuthorizationMatrix() { }

    public static boolean allows(ProjectRole role, ResourceAction action) {
        return role != null && action != null && ALLOWED.getOrDefault(role, Set.of()).contains(action);
    }
}
