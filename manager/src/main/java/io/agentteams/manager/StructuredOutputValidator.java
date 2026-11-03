package io.agentteams.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    public ConversationIntent parseConversationIntent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) throw invalid();
            String intent = root.path("intent").asText();
            if ("CHAT".equals(intent)) return ConversationIntent.chat(requiredText(root, "response"));
            if ("CLARIFY".equals(intent)) return ConversationIntent.clarify(requiredText(root, "response"));
            if ("CREATE_TASK".equals(intent)) {
                List<String> capabilities = new ArrayList<>();
                JsonNode values = root.path("required_capabilities");
                if (values.isArray()) values.forEach(value -> {
                    if (value.isTextual()) capabilities.add(value.asText());
                });
                CreateTaskIntent task = new CreateTaskIntent("CREATE_TASK", requiredText(root, "title"),
                        root.path("description").asText(""), capabilities, root.path("priority").asInt(0),
                        root.path("requires_approval").asBoolean(false));
                return ConversationIntent.createTask(task);
            }
            throw invalid();
        } catch (IOException | IllegalArgumentException error) {
            throw new InvalidModelOutputException("model output failed conversation intent validation", error);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText();
        if (value == null || value.isBlank()) throw invalid();
        return value.trim();
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("JSON object required"); }
}
