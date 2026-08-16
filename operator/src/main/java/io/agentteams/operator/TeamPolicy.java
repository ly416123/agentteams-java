package io.agentteams.operator;

public record TeamPolicy(int maxConcurrentTasks, boolean requireApproval) {
    public TeamPolicy {
        if (maxConcurrentTasks <= 0) throw new IllegalArgumentException("maxConcurrentTasks must be positive");
    }
}
