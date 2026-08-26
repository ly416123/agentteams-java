package io.agentteams.controlplane.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

public final class ConfigManifestCanonicalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigManifestCanonicalizer() {
    }

    public static String normalize(String manifest) {
        try {
            return MAPPER.writeValueAsString(canonicalize(MAPPER.readTree(manifest)));
        } catch (Exception error) {
            throw new IllegalArgumentException("manifestJson must be valid JSON", error);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isValueNode()) return node;
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            var unique = new java.util.TreeMap<String, JsonNode>();
            node.forEach(value -> {
                JsonNode canonical = canonicalize(value);
                unique.putIfAbsent(canonical.toString(), canonical);
            });
            unique.values().forEach(result::add);
            return result;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        var sorted = new ArrayList<Map.Entry<String, JsonNode>>();
        fields.forEachRemaining(sorted::add);
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        sorted.forEach(field -> result.set(field.getKey(), canonicalize(field.getValue())));
        return result;
    }
}
