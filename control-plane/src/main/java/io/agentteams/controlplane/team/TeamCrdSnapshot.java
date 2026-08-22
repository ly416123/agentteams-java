package io.agentteams.controlplane.team;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TeamCrdSnapshot(UUID id, String namespace, String resourceName, String name,
        String resourceVersion, UUID leaderId, List<Member> members, Policy policy) {
    public TeamCrdSnapshot {
        Objects.requireNonNull(id, "id");
        requireText(namespace, "namespace");
        requireText(resourceName, "resourceName");
        requireText(name, "name");
        requireText(resourceVersion, "resourceVersion");
        Objects.requireNonNull(leaderId, "leaderId");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(policy, "policy");
    }

    public record Member(UUID agentId, String role, List<String> declaredCapabilities) {
        public Member {
            Objects.requireNonNull(agentId, "agentId");
            requireText(role, "role");
            declaredCapabilities = List.copyOf(Objects.requireNonNull(declaredCapabilities,
                    "declaredCapabilities"));
        }
    }

    public record Policy(int maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities) {
        public Policy {
            if (maxConcurrentTasks < 1) {
                throw new IllegalArgumentException("maxConcurrentTasks must be positive");
            }
            allowedRuntimes = List.copyOf(Objects.requireNonNull(allowedRuntimes, "allowedRuntimes"));
            requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities,
                    "requiredCapabilities"));
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
