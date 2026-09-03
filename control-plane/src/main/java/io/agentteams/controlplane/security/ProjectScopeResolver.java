package io.agentteams.controlplane.security;

import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Resolves a project name or UUID once, with tenant and active-membership checks. */
@Service
public final class ProjectScopeResolver {
    private final ProjectRepository projects;

    public ProjectScopeResolver(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public ProjectScope resolve(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        return resolve(principal, principal.scope().project());
    }

    public ProjectScope resolve(Principal principal, String requestedProject) {
        Objects.requireNonNull(principal, "principal");
        String projectValue = requestedProject == null || requestedProject.isBlank()
                ? principal.scope().project() : requestedProject.trim();
        ProjectRecord project = find(principal.scope().tenant(), projectValue)
                .filter(candidate -> "ACTIVE".equals(candidate.status()))
                .orElseThrow(() -> new AuthorizationException("project scope not found"));
        projects.findMembership(project.tenantId(), project.id(), principal.subject())
                .orElseThrow(() -> new AuthorizationException("project membership denied"));
        return new ProjectScope(project.tenantId(), project.id(), project.name(), principal.scope().team());
    }

    public Principal canonicalize(Principal principal, String requestedProject) {
        ProjectScope scope = resolve(principal, requestedProject);
        return new Principal(principal.subject(), scope.authorizationScope(), principal.permissions());
    }

    private java.util.Optional<ProjectRecord> find(String tenantId, String projectValue) {
        try {
            return projects.findProject(tenantId, UUID.fromString(projectValue));
        } catch (IllegalArgumentException notUuid) {
            return projects.findProjectByName(tenantId, projectValue);
        }
    }
}
