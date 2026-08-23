package io.agentteams.controlplane.agentspec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Parses only the resource-reference portion of an already schema-validated AgentSpec. */
public final class AgentSpecReferenceParser {

    private final ObjectMapper objectMapper;

    public AgentSpecReferenceParser() {
        this(new ObjectMapper());
    }

    public AgentSpecReferenceParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public AgentSpecReferences parse(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw new IllegalArgumentException("spec must be a JSON object");
        }
        try {
            JsonNode root = objectMapper.readTree(specJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("spec must be a JSON object");
            }
            return new AgentSpecReferences(parseModelRef(root.get("modelRef")),
                    parseStringArray(root.get("skillRefs"), "skillRefs"),
                    parseStringArray(root.get("mcpRefs"), "mcpRefs"));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("spec must be valid JSON", error);
        }
    }

    private static AgentSpecReferences.ModelRef parseModelRef(JsonNode modelRef) {
        if (modelRef == null || modelRef.isNull()) {
            return null;
        }
        if (modelRef.isTextual()) {
            String value = modelRef.asText().trim();
            int separator = value.indexOf('/');
            if (separator > 0 && separator < value.length() - 1) {
                return new AgentSpecReferences.ModelRef(value.substring(0, separator),
                        value.substring(separator + 1));
            }
            throw invalid("spec.modelRef must contain provider and model");
        }
        if (!modelRef.isObject() || !modelRef.path("provider").isTextual()
                || !modelRef.path("model").isTextual()) {
            throw invalid("spec.modelRef must be an object with provider and model strings");
        }
        return new AgentSpecReferences.ModelRef(modelRef.path("provider").asText(),
                modelRef.path("model").asText());
    }

    private static List<String> parseStringArray(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw invalid("spec." + field + " must be an array of strings");
        }
        List<String> refs = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid("spec." + field + " must contain non-blank strings");
            }
            refs.add(item.asText());
        }
        return refs;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
