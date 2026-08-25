package io.agentteams.worker.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeTask;

/** Creates an isolated AgentScope Harness session for one runtime attempt. */
@FunctionalInterface
public interface AgentScopeHarnessFactory {
    HarnessAgent create(RuntimeTask task, AgentRuntimeContext context);
}
