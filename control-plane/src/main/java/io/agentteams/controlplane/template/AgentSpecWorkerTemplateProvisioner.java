package io.agentteams.controlplane.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.agentspec.AgentSpecService;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.service.AgentService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Production adapter that composes the existing AgentSpec and Worker creation boundaries. */
@Service
public final class AgentSpecWorkerTemplateProvisioner implements TemplateInstanceProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentSpecService specs;
    private final AgentService agents;

    public AgentSpecWorkerTemplateProvisioner(AgentSpecService specs, AgentService agents) {
        this.specs = specs;
        this.agents = agents;
    }

    @Override
    public ProvisionedInstance provision(WorkerTemplateRevision revision, UUID instanceId, String idempotencyKey) {
        try {
            JsonNode json = MAPPER.readTree(revision.specJson());
            String runtime = required(json, "runtime", "java");
            String provider = required(json, "modelProvider", null);
            String model = required(json, "modelName", null);
            String name = "template-worker-" + instanceId;
            var spec = specs.create("template-spec-" + instanceId,
                    new AgentSpecService.Input(name, runtime, provider, model, null, "RUNNING", revision.specJson()));
            AgentRecord worker = agents.create("template-worker-" + instanceId,
                    new AgentService.AgentInput(name, runtime, "{}", "{}"));
            return new ProvisionedInstance(spec.id(), worker.id());
        } catch (Exception error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("template spec must be valid JSON object", error);
        }
    }

    private static String required(JsonNode json, String field, String defaultValue) {
        JsonNode value = json == null ? null : json.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            if (defaultValue != null) return defaultValue;
            throw new IllegalArgumentException("template spec field is required: " + field);
        }
        return value.asText().trim();
    }
}
