package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Enforces memory scope, consent and sensitivity boundaries before model use. */
@Service
public final class MemoryPolicyService {
    public boolean canRead(MemoryPolicy policy, ExecutionContext context) {
        return canRead(policy, context, null);
    }

    public boolean canRead(MemoryPolicy policy, ExecutionContext context, UUID taskId) {
        if (policy == null || context == null) return false;
        if (!policy.organizationId().equals(context.organizationId())
                || !policy.tenantId().equals(context.tenantId())) return false;
        return switch (policy.scope()) {
            case USER_PRIVATE -> context.subjectId().equals(policy.subjectId());
            case ORGANIZATION_SHARED -> true;
            case PROJECT_SHARED -> context.projectId().equals(policy.projectId());
            case TEAM_SHARED -> context.teamId().equals(policy.teamId());
            case TASK -> taskId != null && taskId.toString().equals(policy.taskId())
                    && (context.projectId().equals(policy.projectId()) || context.teamId().equals(policy.teamId()));
        };
    }

    public void requireReadable(MemoryPolicy policy, ExecutionContext context) {
        requireReadable(policy, context, null);
    }

    public void requireReadable(MemoryPolicy policy, ExecutionContext context, UUID taskId) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        if (!canRead(policy, context, taskId)) throw new IllegalArgumentException("memory is outside the execution context");
        policy.requireUsableInModelContext();
    }
}
