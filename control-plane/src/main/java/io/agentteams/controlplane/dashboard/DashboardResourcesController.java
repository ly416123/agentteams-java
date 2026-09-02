package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.project.ProjectRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped resource totals used by the console overview. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardResourcesController {
    private final DashboardResourcesService service;
    private final ProjectRepository projects;

    public DashboardResourcesController(DashboardResourcesService service) {
        this(service, null);
    }

    public DashboardResourcesController(DashboardResourcesService service, ProjectRepository projects) {
        this.service = service;
        this.projects = projects;
    }

    @GetMapping("/resources")
    public DashboardResourcesService.Resources resources(
            @RequestParam(name = "projectId", required = false) String projectId) {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        DashboardProjectScope.resolve(principal, projectId, projects);
        return service.summarize();
    }
}
