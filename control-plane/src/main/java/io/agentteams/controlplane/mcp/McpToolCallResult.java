package io.agentteams.controlplane.mcp;

import java.util.List;

/** Result envelope that keeps connector exceptions and audit details out of API callers. */
public record McpToolCallResult(McpOperationOutcome outcome, String classification, Object value) {
    public McpToolCallResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (classification == null || classification.isBlank()) {
            throw new IllegalArgumentException("classification is required");
        }
    }

    public static McpToolCallResult success(Object value) {
        return new McpToolCallResult(McpOperationOutcome.SUCCESS, "SUCCESS", value);
    }

    public static McpToolCallResult failure(McpOperationOutcome outcome, String classification) {
        return new McpToolCallResult(outcome, classification, null);
    }

    public static McpToolCallResult discoverySuccess(List<McpToolDescriptor> tools) {
        return success(List.copyOf(tools));
    }
}
