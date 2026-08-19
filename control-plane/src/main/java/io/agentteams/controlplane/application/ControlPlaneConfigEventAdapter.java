package io.agentteams.controlplane.application;

import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import java.util.Objects;

/** Keeps configuration acknowledgement handling behind the application boundary. */
public final class ControlPlaneConfigEventAdapter implements ConfigEventPort {
    private final ConfigDeploymentService deployments;

    public ControlPlaneConfigEventAdapter(ConfigDeploymentService deployments) {
        this.deployments = Objects.requireNonNull(deployments, "deployments");
    }

    @Override
    public void applied(ConfigAppliedCommand command) {
        deployments.recordApplied(command);
    }
}
