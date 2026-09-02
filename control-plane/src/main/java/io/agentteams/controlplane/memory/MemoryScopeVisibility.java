package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;

/** Centralizes resource-scope checks used by management reads and governance commands. */
final class MemoryScopeVisibility {
    private MemoryScopeVisibility() { }

    static boolean resourceVisible(MemoryRecord memory, ExecutionContext context) {
        MemoryPolicy policy = memory.policy();
        if (!context.organizationId().equals(policy.organizationId())
                || !context.tenantId().equals(policy.tenantId())) {
            return false;
        }
        if (policy.projectId() != null && !context.projectId().equals(policy.projectId())) {
            return false;
        }
        return policy.teamId() == null || context.teamId().equals(policy.teamId());
    }

    static boolean visibleToList(MemoryRecord memory, ExecutionContext context) {
        return resourceVisible(memory, context)
                && (memory.policy().scope() != MemoryPolicy.Scope.USER_PRIVATE
                || context.subjectId().equals(memory.policy().subjectId()));
    }
}
