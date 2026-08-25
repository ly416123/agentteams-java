package io.agentteams.worker.agentscope;

import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;

/** Gate checked before an AgentScope event or terminal result crosses the runtime boundary. */
@FunctionalInterface
public interface AgentScopeWorkspaceActiveGuard {
    void assertActive(RuntimeTask task, AgentRuntimeContext context);

    static AgentScopeWorkspaceActiveGuard noop() {
        return (task, context) -> { };
    }
}
