package io.agentteams.controlplane.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Builds the same minimal memory projection for every task dispatch path. */
@Service
public final class TaskMemoryContextAssembler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextAssemblyService assembly;
    private final ExecutionContextResolver contexts;

    public TaskMemoryContextAssembler(ContextAssemblyService assembly, ExecutionContextResolver contexts) {
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    /** Returns null when the task has no resolvable organization context; never widens access. */
    public String assemble(TaskRecord task) {
        Objects.requireNonNull(task, "task");
        try {
            JsonNode root = JSON.readTree(task.specJson());
            JsonNode scope = root == null ? null : root.get("scope");
            if (scope == null || !scope.isObject()) return null;
            String organization = required(scope, "organizationId");
            String tenant = required(scope, "tenant");
            String project = required(scope, "project");
            String team = required(scope, "team");
            var context = contexts.resolve(new Principal(task.actor(),
                    new AuthorizationService.Scope(tenant, project, team), Set.of()));
            if (!organization.equals(context.organizationId())) return null;
            int budget = root.path("memoryTokenBudget").asInt(4000);
            return assembly.assemble(context, task.id(), budget).toJson();
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    private static String required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }
}
