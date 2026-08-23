package io.agentteams.controlplane.agentspec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/** Validates the portable, runtime-neutral portion of an AgentSpec document. */
public final class AgentSpecSchemaValidator {

    public static final int MAX_SPEC_BYTES = 64 * 1024;
    private static final int MAX_REFERENCES = 100;

    private final ObjectMapper objectMapper;

    public AgentSpecSchemaValidator() {
        this(new ObjectMapper());
    }

    public AgentSpecSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void validate(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw invalid("spec must be a JSON object");
        }
        if (specJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            throw invalid("spec exceeds the maximum size of " + MAX_SPEC_BYTES + " bytes");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(specJson);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("spec must be valid JSON", error);
        }
        if (root == null || !root.isObject()) {
            throw invalid("spec must be a JSON object");
        }
        validateReferences(root, "skillRefs");
        validateReferences(root, "mcpRefs");
        validateModelRef(root.get("modelRef"));
        JsonNode permissions = root.get("permissions");
        if (permissions != null && !permissions.isObject()) {
            throw invalid("spec.permissions must be an object");
        }
        JsonNode resources = root.get("resources");
        if (resources != null && !resources.isObject()) {
            throw invalid("spec.resources must be an object");
        }
    }

    private static void validateModelRef(JsonNode modelRef) {
        if (modelRef == null || modelRef.isNull()) {
            return;
        }
        if (modelRef.isTextual()) {
            String value = modelRef.asText().trim();
            int separator = value.indexOf('/');
            if (separator <= 0 || separator == value.length() - 1) {
                throw invalid("spec.modelRef must contain provider and model");
            }
            return;
        }
        if (!modelRef.isObject() || !modelRef.path("provider").isTextual()
                || modelRef.path("provider").asText().isBlank()
                || !modelRef.path("model").isTextual() || modelRef.path("model").asText().isBlank()) {
            throw invalid("spec.modelRef must be an object with provider and model strings");
        }
    }

    private static void validateReferences(JsonNode root, String field) {
        JsonNode refs = root.get(field);
        if (refs == null) {
            return;
        }
        if (!refs.isArray()) {
            throw invalid("spec." + field + " must be an array of strings");
        }
        if (refs.size() > MAX_REFERENCES) {
            throw invalid("spec." + field + " must contain at most " + MAX_REFERENCES + " references");
        }
        Set<String> unique = new HashSet<>();
        Iterator<JsonNode> iterator = refs.elements();
        while (iterator.hasNext()) {
            JsonNode ref = iterator.next();
            if (!ref.isTextual() || ref.asText().isBlank() || ref.asText().length() > 255
                    || !unique.add(ref.asText().trim())) {
                throw invalid("spec." + field + " must contain unique non-blank references of at most 255 characters");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
