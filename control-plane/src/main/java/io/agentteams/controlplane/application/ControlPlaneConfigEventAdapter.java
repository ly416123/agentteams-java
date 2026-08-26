package io.agentteams.controlplane.application;

import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.team.TeamDeploymentService;
import java.util.Objects;

/** Keeps configuration acknowledgement handling behind the application boundary. */
public final class ControlPlaneConfigEventAdapter implements ConfigEventPort {
    private final ConfigDeploymentService deployments;
    private final TeamDeploymentService teamDeployments;

    public ControlPlaneConfigEventAdapter(ConfigDeploymentService deployments) {
        this(deployments, null);
    }

    public ControlPlaneConfigEventAdapter(ConfigDeploymentService deployments, TeamDeploymentService teamDeployments) {
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.teamDeployments = teamDeployments;
    }

    @Override
    public void applied(ConfigAppliedCommand command) {
        deployments.recordApplied(command);
        if (teamDeployments != null) teamDeployments.recordAck(command);
    }
}
