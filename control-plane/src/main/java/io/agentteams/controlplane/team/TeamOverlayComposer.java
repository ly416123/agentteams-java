package io.agentteams.controlplane.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Composes immutable Agent base config with Team and Task overlays. */
public final class TeamOverlayComposer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String compose(String baseJson, String teamOverlayJson, String taskOverlayJson) {
        ObjectNode result = object(baseJson, "base");
        merge(result, object(teamOverlayJson, "team overlay"));
        merge(result, object(taskOverlayJson, "task overlay"));
        try {
            return MAPPER.writeValueAsString(result);
        } catch (Exception error) {
            throw new IllegalArgumentException("effective configuration cannot be serialized", error);
        }
    }

    private static ObjectNode object(String json, String name) {
        try {
            JsonNode value = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (value == null || !value.isObject()) throw new IllegalArgumentException(name + " must be a JSON object");
            return (ObjectNode) value;
        } catch (Exception error) {
            throw new IllegalArgumentException(name + " must be valid JSON", error);
        }
    }

    private static void merge(ObjectNode target, ObjectNode overlay) {
        overlay.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            JsonNode value = entry.getValue();
            if (current != null && current.isObject() && value.isObject()) {
                merge((ObjectNode) current, (ObjectNode) value);
            } else {
                target.set(entry.getKey(), value.deepCopy());
            }
        });
    }
}
