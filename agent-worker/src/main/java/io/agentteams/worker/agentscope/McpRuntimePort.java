package io.agentteams.worker.agentscope;

import io.agentscope.core.tool.Toolkit;
import io.agentteams.runtime.RuntimeMcpServer;
import java.util.List;
import java.util.Set;
import io.agentteams.application.api.SkillCapabilityPolicy;

/** Worker boundary for registering resolved MCP servers into an AgentScope toolkit. */
public interface McpRuntimePort {
    void configure(Toolkit toolkit, List<RuntimeMcpServer> servers);

    /** Configures MCP with the active Skill capability policies at the final Worker boundary. */
    default void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<SkillCapabilityPolicy> policies) {
        configure(toolkit, servers);
    }

    /** Configures the task-scoped MCP view, including platform orchestration tools. */
    default void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<SkillCapabilityPolicy> policies, Set<String> requiredTaskTools) {
        configure(toolkit, servers, policies);
    }

    /** Configures task MCP tools with assignment metadata that is never exposed to the model. */
    default void configure(Toolkit toolkit, List<RuntimeMcpServer> servers,
            List<SkillCapabilityPolicy> policies, Set<String> requiredTaskTools,
            McpExecutionContext executionContext) {
        configure(toolkit, servers, policies, requiredTaskTools);
    }
}
