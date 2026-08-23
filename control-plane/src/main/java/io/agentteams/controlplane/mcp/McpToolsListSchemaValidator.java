package io.agentteams.controlplane.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Minimal fail-closed validator for the JSON-RPC tools/list response shape. */
public final class McpToolsListSchemaValidator {
    private final ObjectMapper objectMapper;

    public McpToolsListSchemaValidator() {
        this(new ObjectMapper());
    }

    public McpToolsListSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<McpToolDescriptor> validate(String json) {
        try {
            return validate(objectMapper.readTree(json));
        } catch (JsonProcessingException | RuntimeException error) {
            if (error instanceof McpHttpConnectorException connectorException) throw connectorException;
            throw protocol("tools/list response is not valid JSON", error);
        }
    }

    public List<McpToolDescriptor> validate(JsonNode root) {
        if (root == null || !root.isObject() || !"2.0".equals(text(root, "jsonrpc"))) {
            throw protocol("tools/list response has an invalid JSON-RPC envelope", null);
        }
        if (root.has("error")) {
            throw protocol("tools/list response contains a JSON-RPC error", null);
        }
        return validateResult(root.get("result"));
    }

    /** Validates the result object after a transport has already checked the JSON-RPC envelope. */
    public List<McpToolDescriptor> validateResult(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw protocol("tools/list response result must be an object", null);
        }
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            throw protocol("tools/list result.tools must be an array", null);
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Iterator<JsonNode> iterator = tools.elements();
        while (iterator.hasNext()) {
            JsonNode tool = iterator.next();
            if (tool == null || !tool.isObject()) {
                throw protocol("tools/list tool must be an object", null);
            }
            String name = text(tool, "name");
            if (name == null || name.isBlank() || !names.add(name)) {
                throw protocol("tools/list tool name is missing or duplicated", null);
            }
            JsonNode description = tool.get("description");
            if (description != null && !description.isTextual()) {
                throw protocol("tools/list tool description must be a string", null);
            }
            JsonNode inputSchema = tool.get("inputSchema");
            if (inputSchema == null || !inputSchema.isObject()) {
                throw protocol("tools/list tool inputSchema must be an object", null);
            }
            descriptors.add(new McpToolDescriptor(name,
                    description == null ? "" : description.textValue(), inputSchema.toString()));
        }
        return List.copyOf(descriptors);
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static McpHttpConnectorException protocol(String message, Throwable cause) {
        return new McpHttpConnectorException(McpHttpFailureCategory.PROTOCOL_ERROR, message, cause);
    }
}
