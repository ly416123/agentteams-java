package io.agentteams.manager;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManagerToolRegistry {
    private final Map<String, Tool> tools;

    public ManagerToolRegistry(Map<String, Tool> tools) { this.tools = Map.copyOf(Objects.requireNonNull(tools, "tools")); }

    public Object invoke(String name, Object input, ToolContext context) {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown manager tool: " + name);
        if (!context.permissions().contains(tool.requiredPermission())) {
            throw new SecurityException("permission denied: " + tool.requiredPermission());
        }
        if (tool.requiresApproval() && !context.approved()) throw new ApprovalRequiredException(name);
        return tool.handler().apply(input);
    }

    public record Tool(String requiredPermission, boolean requiresApproval, java.util.function.Function<Object, Object> handler) {
        public Tool { if (requiredPermission == null || requiredPermission.isBlank()) throw new IllegalArgumentException("permission required");
            Objects.requireNonNull(handler, "handler"); }
    }

    public record ToolContext(Set<String> permissions, boolean approved) {
        public ToolContext { permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions")); }
    }
}
