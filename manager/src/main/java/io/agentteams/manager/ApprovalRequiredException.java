package io.agentteams.manager;

public class ApprovalRequiredException extends RuntimeException {
    public ApprovalRequiredException(String tool) { super("approval required for tool: " + tool); }
}
