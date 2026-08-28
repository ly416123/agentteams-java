package io.agentteams.worker.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.agentteams.runtime.RuntimeTask;
import java.util.UUID;

/** Creates an isolated AgentScope Harness session for one runtime attempt. */
@FunctionalInterface
public interface AgentScopeHarnessFactory {
    HarnessAgent create(RuntimeTask task, AgentRuntimeContext context);

    /** Releases any workspace binding retained for a task after termination or failed startup. */
    default void release(UUID taskId) { }

    /** Releases all retained bindings when the runtime stops. */
    default void releaseAll() { }

    /** Activates the immutable Skill directories prepared with a runtime configuration. */
    default void applyConfig(RuntimeConfigSnapshot snapshot) { }
}
