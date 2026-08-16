package io.agentteams.operator;

import java.util.List;
import java.util.Objects;

public record TeamMember(String agentRef, String role, List<String> capabilities) {
    public TeamMember {
        if (agentRef == null || agentRef.isBlank()) throw new IllegalArgumentException("agentRef must not be blank");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role must not be blank");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }
}
