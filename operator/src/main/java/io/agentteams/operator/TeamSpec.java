package io.agentteams.operator;

import java.util.List;
import java.util.Objects;

public record TeamSpec(String leaderRef, List<TeamMember> members, TeamPolicy policy,
        String workspaceRef, String channelBindingRef) {
    public TeamSpec {
        if (leaderRef == null || leaderRef.isBlank()) throw new IllegalArgumentException("leaderRef must not be blank");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        Objects.requireNonNull(policy, "policy");
        if (workspaceRef == null || workspaceRef.isBlank()) throw new IllegalArgumentException("workspaceRef must not be blank");
        if (channelBindingRef == null || channelBindingRef.isBlank()) throw new IllegalArgumentException("channelBindingRef must not be blank");
    }
}
