package io.agentteams.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;

public final class StructuredOutputValidator {
    private final ObjectMapper mapper;

    public StructuredOutputValidator(ObjectMapper mapper) { this.mapper = java.util.Objects.requireNonNull(mapper, "mapper"); }

    public CreateTaskIntent parseCreateTask(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            var capabilities = new ArrayList<String>();
            JsonNode values = root.path("required_capabilities");
            if (values.isArray()) values.forEach(value -> { if (value.isTextual()) capabilities.add(value.asText()); });
            return new CreateTaskIntent(root.path("intent").asText(), root.path("title").asText(),
                    root.path("description").asText(), capabilities, root.path("priority").asInt(0),
                    root.path("requires_approval").asBoolean(false));
        } catch (IOException | IllegalArgumentException error) {
            throw new InvalidModelOutputException("model output failed structured validation", error);
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("JSON object required"); }
}
