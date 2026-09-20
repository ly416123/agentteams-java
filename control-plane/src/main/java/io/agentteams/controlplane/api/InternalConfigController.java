package io.agentteams.controlplane.api;

import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cluster-internal configuration bootstrap boundary used by provisioned Workers. */
@RestController
@RequestMapping("/internal/config")
public final class InternalConfigController {
    private final ConfigDeploymentService deployments;

    public InternalConfigController(ConfigDeploymentService deployments) {
        this.deployments = deployments;
    }

    @GetMapping("/agents/{agentId}/current")
    public List<ConfigController.CurrentConfigResponse> current(@PathVariable UUID agentId) {
        return deployments.findCurrentForAgent(agentId).stream()
                .peek(current -> PrincipalContext.requireScope(current.snapshot().manifestJson()))
                .map(ConfigController.CurrentConfigResponse::from)
                .toList();
    }
}
