package io.agentteams.controlplane.security;

public enum Permission {
    TASK_READ("task:read"),
    TASK_CREATE("task:create"),
    TASK_CANCEL("task:cancel"),
    TASK_RETRY("task:retry"),
    TASK_PAUSE("task:pause"),
    TASK_APPROVE("task:approve"),
    TASK_REJECT("task:reject"),
    AGENT_READ("agent:read"),
    AGENT_WRITE("agent:write"),
    ARTIFACT_READ("artifact:read"),
    ARTIFACT_WRITE("artifact:write"),
    TEAM_READ("team:read"),
    TEAM_WRITE("team:write"),
    TEAM_APPROVE("team:approve"),
    CONFIG_READ("config:read"),
    CONFIG_WRITE("config:write"),
    MODEL_READ("model:read"),
    MODEL_WRITE("model:write"),
    AGENT_SPEC_READ("agent-spec:read"),
    AGENT_SPEC_WRITE("agent-spec:write"),
    SKILL_READ("skill:read"),
    SKILL_WRITE("skill:write"),
    MCP_READ("mcp:read"),
    MCP_WRITE("mcp:write"),
    USAGE_READ("usage:read"),
    QUOTA_WRITE("quota:write"),
    AUDIT_READ("audit:read");

    private final String value;

    Permission(String value) { this.value = value; }

    public String value() { return value; }
}
