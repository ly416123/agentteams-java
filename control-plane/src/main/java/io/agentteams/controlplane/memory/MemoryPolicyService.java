package io.agentteams.controlplane.memory;

import io.agentteams.application.api.MemoryPolicy;
import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Enforces memory scope, consent and sensitivity boundaries before model use. */
@Service
public final class MemoryPolicyService {
    public boolean canRead(MemoryPolicy policy, ExecutionContext context) {
        if (policy == null || context == null) return false;
        if (!policy.organizationId().equals(context.organizationId())
                || !policy.tenantId().equals(context.tenantId())) return false;
        return switch (policy.scope()) {
            case USER_PRIVATE -> context.subjectId().equals(policy.subjectId());
            case ORGANIZATION_SHARED -> true;
            case PROJECT_SHARED -> context.projectId().equals(policy.projectId());
            case TEAM_SHARED -> context.teamId().equals(policy.teamId());
            case TASK -> context.projectId().equals(policy.projectId()) || context.teamId().equals(policy.teamId());
        };
    }

    public void requireReadable(MemoryPolicy policy, ExecutionContext context) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        if (!canRead(policy, context)) throw new IllegalArgumentException("memory is outside the execution context");
        policy.requireUsableInModelContext();
    }
}
