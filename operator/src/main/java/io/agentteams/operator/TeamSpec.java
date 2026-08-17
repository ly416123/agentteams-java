package io.agentteams.operator;

import java.util.List;
import java.util.Objects;

/** Kubernetes-friendly mutable spec with immutable-style accessors for application code. */
public final class TeamSpec {
    private String leaderRef;
    private List<TeamMember> members;
    private TeamPolicy policy;
    private String workspaceRef;
    private String channelBindingRef;

    public TeamSpec() {
        this.leaderRef = "";
        this.members = List.of();
        this.policy = new TeamPolicy(1, false);
        this.workspaceRef = "";
        this.channelBindingRef = "";
    }

    public TeamSpec(String leaderRef, List<TeamMember> members, TeamPolicy policy,
            String workspaceRef, String channelBindingRef) {
        setLeaderRef(leaderRef);
        setMembers(members);
        setPolicy(policy);
        setWorkspaceRef(workspaceRef);
        setChannelBindingRef(channelBindingRef);
    }

    public String leaderRef() { return leaderRef; }
    public List<TeamMember> members() { return members; }
    public TeamPolicy policy() { return policy; }
    public String workspaceRef() { return workspaceRef; }
    public String channelBindingRef() { return channelBindingRef; }

    public String getLeaderRef() { return leaderRef; }
    public void setLeaderRef(String value) { leaderRef = requireText(value, "leaderRef"); }
    public List<TeamMember> getMembers() { return members; }
    public void setMembers(List<TeamMember> value) { members = List.copyOf(Objects.requireNonNull(value, "members")); }
    public TeamPolicy getPolicy() { return policy; }
    public void setPolicy(TeamPolicy value) { policy = Objects.requireNonNull(value, "policy"); }
    public String getWorkspaceRef() { return workspaceRef; }
    public void setWorkspaceRef(String value) { workspaceRef = requireText(value, "workspaceRef"); }
    public String getChannelBindingRef() { return channelBindingRef; }
    public void setChannelBindingRef(String value) { channelBindingRef = requireText(value, "channelBindingRef"); }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
