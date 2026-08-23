package io.agentteams.controlplane.mcp;

import java.util.Objects;

/** Metadata returned by an MCP tools/list adapter. */
public record McpToolDescriptor(String name, String description, String inputSchemaJson) {
    public McpToolDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        description = description == null ? "" : description;
        inputSchemaJson = inputSchemaJson == null ? "{}" : inputSchemaJson;
    }
}
