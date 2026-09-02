package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.Principal;
import java.util.UUID;

/** Resolves the console's UUID selector without widening the caller's logical project scope. */
final class DashboardProjectScope {
    private DashboardProjectScope() { }

    static String resolve(Principal principal, String requestedProject, ProjectRepository projects) {
        String authenticatedProject = principal.scope().project();
        if (requestedProject == null || authenticatedProject.equals(requestedProject)) {
            return authenticatedProject;
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(requestedProject);
        } catch (IllegalArgumentException e) {
            throw outsideScope();
        }
        if (projects == null) throw outsideScope();
        ProjectRecord project = projects.findProject(principal.scope().tenant(), projectId)
                .filter(candidate -> candidate.status().equals("ACTIVE"))
                .filter(candidate -> candidate.name().equals(authenticatedProject))
                .orElseThrow(DashboardProjectScope::outsideScope);
        ProjectMembershipRecord membership = projects.findMembership(principal.scope().tenant(), project.id(),
                        principal.subject())
                .filter(candidate -> candidate.status().equals("ACTIVE"))
                .orElseThrow(DashboardProjectScope::outsideScope);
        return project.name();
    }

    private static AuthorizationException outsideScope() {
        return new AuthorizationException("project is outside the caller scope");
    }
}
