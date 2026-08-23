package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.team.TeamSchedulingPolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class TeamService {
    private final FoundationPersistenceService persistence;
    private final TeamSchedulingPolicy schedulingPolicy;
    private final ResourceScopeRepository resourceScopes;

    public TeamService(FoundationPersistenceService persistence) {
        this(persistence, new TeamSchedulingPolicy(), null);
    }

    public TeamService(FoundationPersistenceService persistence, TeamSchedulingPolicy schedulingPolicy) {
        this(persistence, schedulingPolicy, null);
    }

    public TeamService(FoundationPersistenceService persistence, TeamSchedulingPolicy schedulingPolicy,
            ResourceScopeRepository resourceScopes) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.schedulingPolicy = Objects.requireNonNull(schedulingPolicy, "schedulingPolicy");
        this.resourceScopes = resourceScopes;
    }

    public TeamRecord create(String name, String displayName, TeamPolicyRecord policy, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        TeamRecord team = TeamRecord.create(UUID.randomUUID(), name, displayName, now);
        TeamPolicyRecord teamPolicy = new TeamPolicyRecord(team.id(), policy.maxConcurrentTasks(),
                policy.requireHumanApproval(), policy.allowedRuntimes(), policy.requiredCapabilities(),
                now, 0);
        TeamRecord result = persistence.inTransaction(tx -> {
            tx.teams().insert(team);
            tx.teams().insertPolicy(teamPolicy);
            return team;
        });
        bindIfAuthenticated(result.id(), result.createdAt());
        requireVisible(result.id());
        return result;
    }

    public TeamMemberRecord addMember(UUID teamId, UUID agentId, String role, Instant now) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(agentId, "agentId");
        requireVisible(teamId);
        requireWorkerVisible(agentId);
        TeamMemberRecord member = new TeamMemberRecord(UUID.randomUUID(), teamId, agentId, role,
                "ACTIVE", now, now, 0);
        return persistence.inTransaction(tx -> {
            tx.teams().findById(teamId).orElseThrow(() -> new ResourceNotFoundException("team", teamId));
            tx.agents().findById(agentId).orElseThrow(() -> new ResourceNotFoundException("agent", agentId));
            tx.teams().insertMember(member);
            return member;
        });
    }

    public TeamSchedulingPolicy.Decision canAssign(UUID teamId, TeamSchedulingPolicy.AssignmentRequest request) {
        requireVisible(teamId);
        return persistence.inTransaction(tx -> {
            TeamRecord team = tx.teams().findById(teamId).orElseThrow(() -> new ResourceNotFoundException("team", teamId));
            if (!"ACTIVE".equals(team.status())) return new TeamSchedulingPolicy.Decision(false, "TEAM_INACTIVE");
            TeamPolicyRecord policy = tx.teams().findPolicy(teamId)
                    .orElseThrow(() -> new IllegalStateException("team policy is missing"));
            for (TeamMemberRecord member : tx.teams().activeMembers(teamId)) {
                AgentRecord agent = tx.agents().findById(member.agentId()).orElse(null);
                if (agent == null) continue;
                TeamSchedulingPolicy.Decision decision = schedulingPolicy.evaluate(policy, member, agent, request,
                        tx.teams().activeAssignmentCount(teamId));
                if (decision.allowed()) return decision;
            }
            return new TeamSchedulingPolicy.Decision(false, "NO_ELIGIBLE_MEMBER");
        });
    }

    public void linkTask(UUID teamId, UUID taskId, boolean approvalRequired, Instant now) {
        requireVisible(teamId);
        persistence.inTransaction(tx -> {
            tx.teams().findById(teamId).orElseThrow(() -> new ResourceNotFoundException("team", teamId));
            tx.tasks().findById(taskId).orElseThrow(() -> new ResourceNotFoundException("task", taskId));
            tx.teams().linkTask(teamId, taskId, approvalRequired ? "PENDING" : "NOT_REQUIRED", now);
            return null;
        });
    }

    private void bindIfAuthenticated(UUID resourceId, Instant createdAt) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("TEAM", resourceId, principal, createdAt));
        }
    }

    private void requireVisible(UUID resourceId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("TEAM", resourceId);
        }
    }

    private void requireWorkerVisible(UUID resourceId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("WORKER", resourceId);
        }
    }
}
