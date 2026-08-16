package io.agentteams.controlplane.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TeamPolicyRecord(UUID teamId, int maxConcurrentTasks, boolean requireHumanApproval,
        List<String> allowedRuntimes, List<String> requiredCapabilities, Instant updatedAt, long version) {
    public TeamPolicyRecord {
        Objects.requireNonNull(teamId, "teamId");
        if (maxConcurrentTasks < 1) throw new IllegalArgumentException("maxConcurrentTasks must be positive");
        allowedRuntimes = List.copyOf(Objects.requireNonNull(allowedRuntimes, "allowedRuntimes"));
        requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static TeamPolicyRecord defaults(UUID teamId, Instant now) {
        return new TeamPolicyRecord(teamId, 1, false, List.of(), List.of(), now, 0);
    }
}
