package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped resource totals used by the console overview. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardResourcesController {
    private final DashboardResourcesService service;

    public DashboardResourcesController(DashboardResourcesService service) {
        this.service = service;
    }

    @GetMapping("/resources")
    public DashboardResourcesService.Resources resources(
            @RequestParam(name = "projectId", required = false) String projectId) {
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new AuthorizationException("authentication required"));
        if (projectId != null && !principal.scope().project().equals(projectId)) {
            throw new AuthorizationException("project is outside the caller scope");
        }
        return service.summarize();
    }
}
