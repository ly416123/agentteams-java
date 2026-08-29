package io.agentteams.manager.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Emits only the small, non-sensitive event contract exposed to managers. */
public final class ManagerEventRedactor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED = Set.of("status", "messageid", "tool", "operationid", "type",
            "version", "phase", "failurecategory", "createdat", "updatedat");

    private ManagerEventRedactor() { }

    public static String redact(String payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            if (root == null || !root.isObject()) return "{\"redacted\":true}";
            JsonNode projected = project(root);
            return JSON.writeValueAsString(projected);
        } catch (Exception error) {
            return "{\"redacted\":true}";
        }
    }

    private static JsonNode project(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!ALLOWED.contains(normalize(entry.getKey()))) return;
                JsonNode value = project(entry.getValue());
                if (value.isValueNode() || !value.isEmpty()) result.set(entry.getKey(), value);
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            node.elements().forEachRemaining(value -> {
                JsonNode projected = project(value);
                if (projected.isValueNode() || !projected.isEmpty()) result.add(projected);
            });
            return result;
        }
        return node;
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
