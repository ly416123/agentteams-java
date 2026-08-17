package io.agentteams.application.api;

public final class PlatformEventSubjects {
    public static final String AGENT_EXECUTION_EVENTS = "agent.events.*";

    private PlatformEventSubjects() {
    }

    public static String agentExecution(String agentId) {
        if (agentId == null || agentId.isBlank() || agentId.contains(".") || agentId.contains("*")) {
            throw new IllegalArgumentException("agentId must be a non-blank NATS subject token");
        }
        return "agent.events." + agentId;
    }
}
