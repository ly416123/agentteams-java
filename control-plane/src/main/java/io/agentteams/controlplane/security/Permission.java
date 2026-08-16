package io.agentteams.controlplane.security;

public enum Permission {
    TASK_READ("task:read"),
    TASK_CREATE("task:create"),
    TASK_CANCEL("task:cancel"),
    AGENT_READ("agent:read"),
    ARTIFACT_READ("artifact:read"),
    ARTIFACT_WRITE("artifact:write"),
    TEAM_READ("team:read"),
    TEAM_WRITE("team:write"),
    TEAM_APPROVE("team:approve"),
    CONFIG_WRITE("config:write"),
    AUDIT_READ("audit:read");

    private final String value;

    Permission(String value) { this.value = value; }

    public String value() { return value; }
}
