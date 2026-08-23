package io.agentteams.controlplane.agentspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Converts an AgentSpec revision into the existing durable worker config pipeline. */
@Service
public final class AgentSpecDeploymentService {
    private final AgentSpecService specs;
    private final ConfigSnapshotService snapshots;
    private final ConfigDeploymentService deployments;
    private final ObjectMapper mapper;

    public AgentSpecDeploymentService(AgentSpecService specs, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, ObjectMapper mapper) {
        this.specs = Objects.requireNonNull(specs, "specs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public AgentSpecDeployment deploy(UUID specId, UUID agentId, String actor) {
        AgentSpecRecord spec = specs.get(Objects.requireNonNull(specId, "specId"));
        String subject = "agent-spec:" + spec.id();
        ConfigSnapshot snapshot = snapshots.create(subject, manifest(spec), actor);
        ConfigDeploymentService.ConfigDeployment deployment = deployments.deploy(agentId, subject, snapshot);
        return new AgentSpecDeployment(spec, snapshot, deployment);
    }

    private String manifest(AgentSpecRecord spec) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("apiVersion", "agentteams.io/v1");
            root.put("kind", "AgentSpec");
            root.put("agentSpecId", spec.id().toString());
            root.put("agentSpecVersion", spec.version());
            root.put("name", spec.name());
            root.put("runtime", spec.runtime());
            root.put("modelProvider", spec.modelProvider());
            root.put("modelName", spec.modelName());
            if (spec.teamRef() != null) root.put("teamRef", spec.teamRef());
            root.put("desiredState", spec.desiredState());
            root.set("spec", mapper.readTree(spec.specJson()));
            return mapper.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("agent spec cannot be converted to a config manifest", error);
        }
    }

    public record AgentSpecDeployment(AgentSpecRecord spec, ConfigSnapshot snapshot,
            ConfigDeploymentService.ConfigDeployment deployment) {
        public AgentSpecDeployment {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(deployment, "deployment");
        }
    }
}
