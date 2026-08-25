package io.agentteams.worker.agentscope;

import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;

/** Revalidates the attempt workspace immediately before runtime side effects. */
@FunctionalInterface
public interface WorkspaceActiveGuard {
    void assertActive(RuntimeTask task, AgentRuntimeContext context);

    static WorkspaceActiveGuard noop() {
        return (task, context) -> { };
    }
}
