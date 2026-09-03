package io.agentteams.controlplane.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.agentspec.AgentSpecService;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.worker.NoopWorkerCrdProvisioner;
import io.agentteams.controlplane.worker.WorkerCrdProvisioner;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Production adapter that composes the existing AgentSpec and Worker creation boundaries. */
@Service
public final class AgentSpecWorkerTemplateProvisioner implements TemplateInstanceProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentSpecService specs;
    private final AgentService agents;
    private final WorkerCrdProvisioner workerCrdProvisioner;
    private final String workerImage;
    private final int workerReplicas;
    private final String gatewayHost;
    private final int gatewayPort;
    private final String configManifestBaseUrl;
    private final String qwenPawEndpoint;
    private final String tlsSecret;

    public AgentSpecWorkerTemplateProvisioner(AgentSpecService specs, AgentService agents) {
        this(specs, agents, new NoopWorkerCrdProvisioner(),
                "ghcr.io/ly416123/agentteams-agent-worker:latest", 1,
                "agentteams-agentteams-java-gateway", 9090,
                "http://agentteams-agentteams-java-control-plane:8080", "http://qwenpaw:8088", "");
    }

    @Autowired
    public AgentSpecWorkerTemplateProvisioner(AgentSpecService specs, AgentService agents,
            ObjectProvider<WorkerCrdProvisioner> provisioners,
            @Value("${agentteams.worker-provisioner.image:ghcr.io/ly416123/agentteams-agent-worker:latest}") String workerImage,
            @Value("${agentteams.worker-provisioner.replicas:1}") int workerReplicas,
            @Value("${agentteams.worker-provisioner.gateway-host:agentteams-agentteams-java-gateway}") String gatewayHost,
            @Value("${agentteams.worker-provisioner.gateway-port:9090}") int gatewayPort,
            @Value("${agentteams.worker-provisioner.config-manifest-base-url:http://agentteams-agentteams-java-control-plane:8080}") String configManifestBaseUrl,
            @Value("${agentteams.worker-provisioner.qwenpaw-endpoint:http://qwenpaw:8088}") String qwenPawEndpoint,
            @Value("${agentteams.worker-provisioner.tls-secret-name:}") String tlsSecret) {
        this(specs, agents, provisioners.getIfAvailable(NoopWorkerCrdProvisioner::new), workerImage, workerReplicas,
                gatewayHost, gatewayPort, configManifestBaseUrl, qwenPawEndpoint, tlsSecret);
    }

    AgentSpecWorkerTemplateProvisioner(AgentSpecService specs, AgentService agents,
            WorkerCrdProvisioner workerCrdProvisioner, String workerImage, int workerReplicas,
            String gatewayHost, int gatewayPort, String configManifestBaseUrl, String qwenPawEndpoint,
            String tlsSecret) {
        this.specs = specs;
        this.agents = agents;
        this.workerCrdProvisioner = workerCrdProvisioner;
        this.workerImage = required(workerImage, "worker image");
        if (workerReplicas < 1) throw new IllegalArgumentException("worker replicas must be positive");
        this.workerReplicas = workerReplicas;
        this.gatewayHost = required(gatewayHost, "gateway host");
        if (gatewayPort < 1 || gatewayPort > 65535) throw new IllegalArgumentException("gateway port is invalid");
        this.gatewayPort = gatewayPort;
        this.configManifestBaseUrl = required(configManifestBaseUrl, "config manifest base URL");
        this.qwenPawEndpoint = required(qwenPawEndpoint, "QwenPaw endpoint");
        this.tlsSecret = tlsSecret == null ? "" : tlsSecret.trim();
    }

    @Override
    public ProvisionedInstance provision(WorkerTemplateRevision revision, UUID instanceId, String idempotencyKey) {
        try {
            JsonNode json = MAPPER.readTree(revision.specJson());
            String runtime = required(json, "runtime", "java");
            String provider = required(json, "modelProvider", null);
            String model = required(json, "modelName", null);
            String name = "template-worker-" + instanceId;
            io.agentteams.controlplane.security.Principal principal = PrincipalContext.current()
                    .orElseThrow(() -> new IllegalStateException("authentication required for Worker provisioning"));
            var spec = specs.create("template-spec-" + instanceId,
                    new AgentSpecService.Input(name, runtime, provider, model, null, "RUNNING", revision.workerType(),
                            revision.specJson()));
            AgentRecord worker = agents.create("template-worker-" + instanceId,
                    new AgentService.AgentInput(name, runtime, revision.workerType(), "{}", scopeMetadata(principal)));
            workerCrdProvisioner.provision(new WorkerCrdProvisioner.Request(
                    worker.id(), runtime, provider, model,
                    principal.scope().tenant(), principal.scope().project(), principal.scope().team(),
                    revision.digest(), "template-" + revision.revision(), "",
                    workerImage, workerReplicas, gatewayHost, gatewayPort, configManifestBaseUrl,
                    qwenPawEndpoint, tlsSecret, java.util.Map.of()));
            return new ProvisionedInstance(spec.id(), worker.id());
        } catch (Exception error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("template spec must be valid JSON object", error);
        }
    }

    private static String scopeMetadata(io.agentteams.controlplane.security.Principal principal) {
        var scope = MAPPER.createObjectNode();
        var values = MAPPER.createObjectNode();
        values.put("tenant", principal.scope().tenant());
        values.put("project", principal.scope().project());
        values.put("team", principal.scope().team());
        scope.set("scope", values);
        return scope.toString();
    }

    private static String required(JsonNode json, String field, String defaultValue) {
        JsonNode value = json == null ? null : json.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("template spec field is required: " + field);
        }
        return value.asText().trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
