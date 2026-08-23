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

    public record ToolContext(Set<String> permissions, boolean approved, String tenantId, String projectId,
            String workerId, String taskId, String teamId, String toolId, String quotaId,
            String quotaDimension) {
        public ToolContext(Set<String> permissions, boolean approved) {
            this(permissions, approved, null, null, null, null, null, null, null, null);
        }

        public ToolContext(Set<String> permissions, boolean approved, String tenantId, String projectId) {
            this(permissions, approved, tenantId, projectId, null, null, null, null, null, null);
        }

        public ToolContext {
            permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
            if ((tenantId == null) != (projectId == null)) {
                throw new IllegalArgumentException("tenantId and projectId must be supplied together");
            }
            if (tenantId != null && (tenantId.isBlank() || projectId.isBlank())) {
                throw new IllegalArgumentException("tenantId and projectId must not be blank");
            }
            requireOptionalText(workerId, "workerId");
            requireOptionalText(taskId, "taskId");
            requireOptionalText(teamId, "teamId");
            requireOptionalText(toolId, "toolId");
            requireOptionalText(quotaId, "quotaId");
            requireOptionalText(quotaDimension, "quotaDimension");
        }

        private static void requireOptionalText(String value, String field) {
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
