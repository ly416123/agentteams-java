package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.ProjectScopeResolver;

/** Resolves the console's UUID selector without widening the caller's logical project scope. */
final class DashboardProjectScope {
    private DashboardProjectScope() { }

    static String resolve(Principal principal, String requestedProject, ProjectRepository projects) {
        if (requestedProject == null || requestedProject.isBlank()
                || principal.scope().project().equals(requestedProject)) {
            return principal.scope().project();
        }
        if (projects == null) return principal.scope().project();
        return new ProjectScopeResolver(projects).resolve(principal, requestedProject).projectIdValue();
    }
}
