package io.agentteams.controlplane.security;

import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies project membership roles to project-scoped API permissions. */
@Service
public final class ProjectApiAuthorization {
    private final ProjectRepository projects;

    public ProjectApiAuthorization(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public Principal withEffectivePermissions(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        var member = membership(principal);
        java.util.Set<String> effective = new java.util.LinkedHashSet<>(principal.permissions());
        for (Permission permission : Permission.values()) {
            if (allows(member.role(), permission)) effective.add(permission.value());
        }
        return new Principal(principal.subject(), principal.scope(), effective);
    }

    private ProjectMembershipRecord membership(Principal principal) {
        UUID projectId;
        try {
            projectId = UUID.fromString(principal.scope().project());
        } catch (IllegalArgumentException invalidScope) {
            throw new AuthorizationException("project scope is not canonical");
        }
        var project = projects.findProject(principal.scope().tenant(), projectId)
                .orElseThrow(() -> new AuthorizationException("project scope not found"));
        return projects.findMembership(principal.scope().tenant(), project.id(), principal.subject())
                .orElseThrow(() -> new AuthorizationException("project membership denied"));
    }

    public void require(Permission permission, Principal principal) {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(principal, "principal");
        ProjectRole role = membership(principal).role();
        if (!allows(role, permission)) {
            throw new AuthorizationException("project permission denied: " + permission.value());
        }
    }

    private static boolean allows(ProjectRole role, Permission permission) {
        if (permission == Permission.TASK_READ || permission == Permission.TEAM_READ
                || permission == Permission.AGENT_READ || permission == Permission.ARTIFACT_READ
                || permission == Permission.MODEL_READ || permission == Permission.AGENT_SPEC_READ
                || permission == Permission.MCP_READ || permission == Permission.SKILL_READ
                || permission == Permission.MEMORY_READ || permission == Permission.SANDBOX_READ
                || permission == Permission.USAGE_READ || permission == Permission.CONFIG_READ) return true;
        if (permission == Permission.TASK_CREATE) return role.atLeast(ProjectRole.DEVELOPER);
        if (permission == Permission.TASK_CANCEL || permission == Permission.TASK_RETRY
                || permission == Permission.TASK_PAUSE) {
            return role.atLeast(ProjectRole.OPERATOR);
        }
        if (permission == Permission.TEAM_WRITE || permission == Permission.TASK_APPROVE
                || permission == Permission.TASK_REJECT || permission == Permission.AGENT_WRITE
                || permission == Permission.ARTIFACT_WRITE || permission == Permission.MODEL_WRITE
                || permission == Permission.AGENT_SPEC_WRITE || permission == Permission.MCP_WRITE
                || permission == Permission.SKILL_WRITE || permission == Permission.MEMORY_GOVERN
                || permission == Permission.AGENT_SPEC_REVIEW || permission == Permission.AGENT_SPEC_PUBLISH
                || permission == Permission.AGENT_SPEC_INSTANTIATE || permission == Permission.AGENT_SPEC_DEPLOY
                || permission == Permission.SKILL_REVIEW || permission == Permission.SKILL_PUBLISH
                || permission == Permission.SKILL_PACKAGE_UPLOAD
                || permission == Permission.MEMORY_EXPORT || permission == Permission.USAGE_EXPORT
                || permission == Permission.CONFIG_WRITE || permission == Permission.QUOTA_WRITE
                || permission == Permission.DASHBOARD_WRITE) {
            return role.atLeast(ProjectRole.ADMIN);
        }
        if (permission == Permission.AUDIT_READ) return role.atLeast(ProjectRole.ADMIN);
        if (permission == Permission.QUOTA_READ) return true;
        return false;
    }
}
