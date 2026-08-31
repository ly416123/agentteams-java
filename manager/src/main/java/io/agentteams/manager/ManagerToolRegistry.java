package io.agentteams.manager;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ManagerToolRegistry {
    private final Map<String, Tool> tools;

    public ManagerToolRegistry(Map<String, Tool> tools) { this.tools = Map.copyOf(Objects.requireNonNull(tools, "tools")); }

    public Object invoke(String name, Object input, ToolContext context) {
        Objects.requireNonNull(context, "context");
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown manager tool: " + name);
        if (!context.permissions().contains(tool.requiredPermission())) {
            throw new SecurityException("permission denied: " + tool.requiredPermission());
        }
        if (tool.requiresApproval() && !context.approved()) throw new ApprovalRequiredException(name);
        return tool.contextualHandler().apply(input, context);
    }

    public record Tool(String requiredPermission, boolean requiresApproval,
            java.util.function.Function<Object, Object> handler,
            java.util.function.BiFunction<Object, ToolContext, Object> contextualHandler) {
        public Tool(String requiredPermission, boolean requiresApproval,
                java.util.function.Function<Object, Object> handler) {
            this(requiredPermission, requiresApproval, handler, (input, ignored) -> handler.apply(input));
        }

        public Tool {
            if (requiredPermission == null || requiredPermission.isBlank()) {
                throw new IllegalArgumentException("permission required");
            }
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(contextualHandler, "contextualHandler");
        }

        public Tool(String requiredPermission, boolean requiresApproval,
                java.util.function.BiFunction<Object, ToolContext, Object> contextualHandler) {
            this(requiredPermission, requiresApproval, input -> {
                throw new IllegalStateException("contextual manager tool requires a ToolContext");
            }, contextualHandler);
        }
    }

    public record ToolContext(Set<String> permissions, boolean approved, String tenantId, String projectId,
            String workerId, String taskId, String teamId, String toolId, String quotaId,
            String quotaDimension, String sessionId) {
        public ToolContext(Set<String> permissions, boolean approved) {
            this(permissions, approved, null, null, null, null, null, null, null, null, null);
        }

        public ToolContext(Set<String> permissions, boolean approved, String tenantId, String projectId) {
            this(permissions, approved, tenantId, projectId, null, null, null, null, null, null, null);
        }

        /** Compatibility constructor for callers that do not associate a Manager session. */
        public ToolContext(Set<String> permissions, boolean approved, String tenantId, String projectId,
                String workerId, String taskId, String teamId, String toolId, String quotaId,
                String quotaDimension) {
            this(permissions, approved, tenantId, projectId, workerId, taskId, teamId, toolId, quotaId,
                    quotaDimension, null);
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
            requireOptionalText(sessionId, "sessionId");
        }

        private static void requireOptionalText(String value, String field) {
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
