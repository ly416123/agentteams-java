package io.agentteams.controlplane.security;

import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Single authorization boundary for scoped project resources. */
@Service
public class ResourceAuthorizationService {
    private final ProjectRepository projects;

    public ResourceAuthorizationService(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public void require(ResourceAction action, ResourceRef resource) {
        Principal principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        if (!principal.scope().tenant().equals(resource.tenantId())
                || (resource.teamId() != null && !principal.scope().team().equals(resource.teamId()))) {
            throw new AuthorizationException("resource is outside the caller scope");
        }
        ProjectMembershipRecord member = projects.findMembership(resource.tenantId(), resource.projectId(),
                principal.subject()).orElseThrow(() -> new AuthorizationException("project membership denied"));
        if (!ResourceAuthorizationMatrix.allows(member.role(), action)) {
            throw new AuthorizationException("permission denied: " + action.name());
        }
    }
}
