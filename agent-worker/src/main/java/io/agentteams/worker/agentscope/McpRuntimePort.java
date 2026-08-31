package io.agentteams.worker.agentscope;

import io.agentscope.core.tool.Toolkit;
import io.agentteams.runtime.RuntimeMcpServer;
import java.util.List;
import io.agentteams.application.api.SkillCapabilityPolicy;

/** Worker boundary for registering resolved MCP servers into an AgentScope toolkit. */
public interface McpRuntimePort {
    void configure(Toolkit toolkit, List<RuntimeMcpServer> servers);

    /** Configures MCP with the active Skill capability policies at the final Worker boundary. */
    default void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<SkillCapabilityPolicy> policies) {
        configure(toolkit, servers);
    }
}
