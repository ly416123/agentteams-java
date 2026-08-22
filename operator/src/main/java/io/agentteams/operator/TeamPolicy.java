package io.agentteams.operator;

import java.util.List;
import java.util.Objects;

public record TeamPolicy(int maxConcurrentTasks, boolean requireApproval,
        List<String> allowedRuntimes, List<String> requiredCapabilities) {
    public TeamPolicy(int maxConcurrentTasks, boolean requireApproval) {
        this(maxConcurrentTasks, requireApproval, List.of(), List.of());
    }

    public TeamPolicy {
        if (maxConcurrentTasks <= 0) throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        allowedRuntimes = List.copyOf(Objects.requireNonNull(allowedRuntimes, "allowedRuntimes"));
        requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        if (allowedRuntimes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("allowedRuntimes must not contain blank values");
        }
        if (requiredCapabilities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("requiredCapabilities must not contain blank values");
        }
    }
}
