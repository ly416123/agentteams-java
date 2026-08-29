package io.agentteams.manager.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.regex.Pattern;

/** Emits only the small, non-sensitive event contract exposed to managers. */
public final class ManagerEventRedactor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED = Set.of("status", "message", "messageid", "tool", "operationid", "type",
            "version", "phase", "failurecategory", "createdat", "updatedat");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{3,}");
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|deepseek[_-]?api[_-]?key|provider[_-]?api[_-]?key|"
                    + "client[_-]?secret|password|token|secret|private[_-]?key|credentials?)"
                    + "\\s*[:=]\\s*[\\\"']?[A-Za-z0-9._~+/=-]{3,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?i)-----BEGIN .*PRIVATE KEY-----");
    private static final Pattern API_TOKEN = Pattern.compile("(?i)\\b(?:sk|rk)-[A-Za-z0-9_-]{3,}\\b");

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
                if (entry.getValue().isTextual() && containsCredential(entry.getValue().textValue())) return;
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
        if (node.isTextual() && containsCredential(node.textValue())) {
            return JSON.getNodeFactory().nullNode();
        }
        return node;
    }

    private static boolean containsCredential(String value) {
        return BEARER.matcher(value).find() || KEY_VALUE.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find() || API_TOKEN.matcher(value).find();
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
