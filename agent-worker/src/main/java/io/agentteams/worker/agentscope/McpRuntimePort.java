package io.agentteams.worker.agentscope;

import io.agentscope.core.tool.Toolkit;
import io.agentteams.runtime.RuntimeMcpServer;
import java.util.List;

/** Worker boundary for registering resolved MCP servers into an AgentScope toolkit. */
public interface McpRuntimePort {
    void configure(Toolkit toolkit, List<RuntimeMcpServer> servers);
}
