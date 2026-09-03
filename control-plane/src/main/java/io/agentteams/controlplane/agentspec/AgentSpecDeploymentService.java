package io.agentteams.controlplane.agentspec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Converts an AgentSpec revision into the existing durable worker config pipeline. */
@Service
public final class AgentSpecDeploymentService {
    private final AgentSpecService specs;
    private final ConfigSnapshotService snapshots;
    private final ConfigDeploymentService deployments;
    private final ObjectMapper mapper;
    private final ResourceScopeRepository resourceScopes;
    private final AgentSpecReferenceValidator referenceValidator;

    public AgentSpecDeploymentService(AgentSpecService specs, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, ObjectMapper mapper) {
        this(specs, snapshots, deployments, mapper, null);
    }

    public AgentSpecDeploymentService(AgentSpecService specs, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, ObjectMapper mapper, ResourceScopeRepository resourceScopes) {
        this(specs, snapshots, deployments, mapper, resourceScopes, new NoopAgentSpecReferenceValidator());
    }

    @Autowired
    public AgentSpecDeploymentService(AgentSpecService specs, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, ObjectMapper mapper, ResourceScopeRepository resourceScopes,
            ObjectProvider<AgentSpecReferenceValidator> referenceValidators) {
        this.specs = Objects.requireNonNull(specs, "specs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.resourceScopes = resourceScopes;
        this.referenceValidator = Objects.requireNonNull(referenceValidators, "referenceValidators")
                .getIfAvailable(NoopAgentSpecReferenceValidator::new);
    }

    AgentSpecDeploymentService(AgentSpecService specs, ConfigSnapshotService snapshots,
            ConfigDeploymentService deployments, ObjectMapper mapper, ResourceScopeRepository resourceScopes,
            AgentSpecReferenceValidator referenceValidator) {
        this.specs = Objects.requireNonNull(specs, "specs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.resourceScopes = resourceScopes;
        this.referenceValidator = Objects.requireNonNull(referenceValidator, "referenceValidator");
    }

    public AgentSpecDeployment deploy(UUID specId, UUID agentId, String actor) {
        AgentSpecRecord spec = specs.get(Objects.requireNonNull(specId, "specId"));
        if (resourceScopes != null && PrincipalContext.current().isPresent()) {
            resourceScopes.requireVisible("WORKER", Objects.requireNonNull(agentId, "agentId"));
        }
        AgentSpecReferenceValidationResult bindings = resolveBindings(spec);
        String subject = "agent-spec:" + spec.id();
        ConfigSnapshot snapshot = snapshots.create(subject, manifest(spec, agentId, bindings.bindings()), actor);
        ConfigDeploymentService.ConfigDeployment deployment = deployments.deploy(agentId, subject, snapshot);
        return new AgentSpecDeployment(spec, snapshot, deployment);
    }

    private AgentSpecReferenceValidationResult resolveBindings(AgentSpecRecord spec) {
        AgentSpecReferences references = new AgentSpecReferenceParser().parse(spec.specJson())
                .withModelRef(new AgentSpecReferences.ModelRef(spec.modelProvider(), spec.modelName()));
        AgentSpecReferenceCatalog.Scope scope = PrincipalContext.current()
                .map(principal -> new AgentSpecReferenceCatalog.Scope(
                        principal.scope().tenant(), principal.scope().project(),
                        spec.teamRef() != null ? spec.teamRef() : principal.scope().team()))
                .orElse(new AgentSpecReferenceCatalog.Scope(spec.tenantId(), spec.projectId(), spec.teamRef()));
        AgentSpecReferenceValidationResult result = referenceValidator.validate(
                new AgentSpecReferenceValidationRequest(scope, references));
        if (!result.isValid()) {
            throw new AgentSpecReferenceValidationException(result);
        }
        return result;
    }

    private String manifest(AgentSpecRecord spec, UUID workerId,
            java.util.List<AgentSpecReferenceBinding> bindings) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("apiVersion", "agentteams.io/v1");
            root.put("kind", "AgentSpec");
            root.put("agentSpecId", spec.id().toString());
            root.put("agentSpecVersion", spec.version());
            root.put("name", spec.name());
            root.put("workerType", spec.workerType().name());
            root.put("runtime", spec.runtime());
            root.put("modelProvider", spec.modelProvider());
            root.put("modelName", spec.modelName());
            if (spec.teamRef() != null) root.put("teamRef", spec.teamRef());
            var principal = PrincipalContext.current();
            String tenant = spec.tenantId() != null ? spec.tenantId()
                    : principal.map(p -> p.scope().tenant()).orElse("default");
            String project = spec.projectId() != null ? spec.projectId()
                    : principal.map(p -> p.scope().project()).orElse("default");
            String team = spec.teamRef() != null ? spec.teamRef()
                    : principal.map(p -> p.scope().team()).orElse("default");
            ObjectNode scope = root.putObject("scope");
            scope.put("tenant", tenant);
            scope.put("project", project);
            scope.put("team", team);
            root.put("desiredState", spec.desiredState());
            root.set("spec", mapper.readTree(spec.specJson()));
            if (!bindings.isEmpty()) {
                var bindingArray = root.putArray("resourceBindings");
                String teamRef = spec.teamRef() != null ? spec.teamRef() : team;
                for (AgentSpecReferenceBinding binding : bindings) {
                    ObjectNode node = bindingArray.addObject();
                    node.put("type", binding.type().name());
                    node.put("reference", binding.reference());
                    node.put("revision", binding.revision());
                    node.put("digest", binding.digest());
                    if (binding.artifactRef() != null) node.put("artifactRef", binding.artifactRef());
                    if (binding.sizeBytes() != null) node.put("sizeBytes", binding.sizeBytes());
                    if (binding.mcpRuntime() != null) {
                        node.put("serverId", binding.mcpRuntime().serverId());
                        node.put("transport", binding.mcpRuntime().transport());
                        node.put("endpoint", binding.mcpRuntime().endpoint());
                        if (binding.mcpRuntime().credentialRef() != null) {
                            node.put("credentialRef", binding.mcpRuntime().credentialRef());
                        }
                    }
                    if (binding.skillCapabilities() != null) {
                        var capabilities = node.putObject("skillCapabilities");
                        var policy = binding.skillCapabilities();
                        capabilities.put("profile", policy.profile().name());
                        capabilities.put("cpuMillicores", policy.cpuMillicores());
                        capabilities.put("memoryMiB", policy.memoryMiB());
                        capabilities.put("ephemeralStorageMiB", policy.ephemeralStorageMiB());
                        capabilities.put("ttlSeconds", policy.ttl().toSeconds());
                        capabilities.put("networkPolicy", policy.networkPolicy().name());
                        capabilities.put("allowSecretReferences", policy.allowSecretReferences());
                        var allowedMcp = capabilities.putArray("allowedMcp");
                        policy.allowedMcp().stream().sorted().forEach(allowedMcp::add);
                        var allowedDomains = capabilities.putArray("allowedDomains");
                        policy.allowedDomains().stream().sorted().forEach(allowedDomains::add);
                        var allowedTools = capabilities.putArray("allowedTools");
                        policy.allowedTools().stream().sorted().forEach(allowedTools::add);
                    }
                    node.put("workerId", workerId.toString());
                    node.put("teamRef", teamRef);
                    ObjectNode bindingScope = node.putObject("scope");
                    bindingScope.put("tenant", binding.tenantId() != null ? binding.tenantId() : tenant);
                    bindingScope.put("project", binding.projectId() != null ? binding.projectId() : project);
                    bindingScope.put("team", binding.teamId() != null ? binding.teamId() : teamRef);
                }
            }
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
