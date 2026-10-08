package io.agentteams.worker.agentscope;

import io.agentteams.runtime.RuntimeTask;
import java.util.Objects;
import java.util.UUID;

/** Trusted assignment context attached by Worker to its platform MCP HTTP requests. */
public record McpExecutionContext(UUID taskId, String attemptId, String leaseId) {

    public McpExecutionContext {
        Objects.requireNonNull(taskId, "taskId");
        attemptId = required(attemptId);
        leaseId = required(leaseId);
    }

    public static McpExecutionContext from(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        String attemptId = task.metadata().get("attemptId");
        String leaseId = task.metadata().get("leaseId");
        if (attemptId == null || attemptId.isBlank() || leaseId == null || leaseId.isBlank()) {
            throw new IllegalArgumentException("task MCP execution context is incomplete");
        }
        return new McpExecutionContext(task.id(), attemptId, leaseId);
    }

    private static String required(String value) {
        Objects.requireNonNull(value, "execution context value");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("execution context value must not be blank");
        return normalized;
    }
}
