package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.team.TeamSchedulingPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TeamService {
    private static final String ADD_MEMBER = "TEAM_ADD_MEMBER";
    private static final String UPDATE_POLICY = "TEAM_UPDATE_POLICY";
    private static final String REMOVE_MEMBER = "TEAM_REMOVE_MEMBER";
    private static final String DELETE_TEAM = "TEAM_DELETE";
    private final FoundationPersistenceService persistence;
    private final TeamSchedulingPolicy schedulingPolicy;
    private final ResourceScopeRepository resourceScopes;
    private final IdempotencyService idempotency;

    public TeamService(FoundationPersistenceService persistence) {
        this(persistence, new TeamSchedulingPolicy(), null, null);
    }

    public TeamService(FoundationPersistenceService persistence, TeamSchedulingPolicy schedulingPolicy) {
        this(persistence, schedulingPolicy, null, null);
    }

    public TeamService(FoundationPersistenceService persistence, TeamSchedulingPolicy schedulingPolicy,
            ResourceScopeRepository resourceScopes) {
        this(persistence, schedulingPolicy, resourceScopes, null);
    }

    public TeamService(FoundationPersistenceService persistence, TeamSchedulingPolicy schedulingPolicy,
            ResourceScopeRepository resourceScopes, IdempotencyService idempotency) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.schedulingPolicy = Objects.requireNonNull(schedulingPolicy, "schedulingPolicy");
        this.resourceScopes = resourceScopes;
        this.idempotency = idempotency;
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

    public TeamRecord create(String idempotencyKey, String name, String displayName,
            TeamPolicyRecord policy, Instant now) {
        if (idempotency == null) {
            throw new IllegalStateException("team idempotency is not configured");
        }
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        String key = idempotency.requireKey(idempotencyKey);
        TeamRecord team = TeamRecord.create(UUID.randomUUID(), name, displayName, now);
        TeamPolicyRecord teamPolicy = new TeamPolicyRecord(team.id(), policy.maxConcurrentTasks(),
                policy.requireHumanApproval(), policy.allowedRuntimes(), policy.requiredCapabilities(), now, 0);
        TeamRecord result = persistence.createTeam(team, teamPolicy, key,
                idempotency.requestHash(name, displayName, Integer.toString(policy.maxConcurrentTasks()),
                        Boolean.toString(policy.requireHumanApproval()), policy.allowedRuntimes().toString(),
                        policy.requiredCapabilities().toString()));
        bindIfAuthenticated(result.id(), result.createdAt());
        requireVisible(result.id());
        return result;
    }

    public TeamMemberRecord addMember(UUID teamId, UUID agentId, String role, Instant now) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    public TeamMemberRecord addMember(UUID teamId, UUID agentId, String role, Instant now, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        requireVisible(teamId);
        requireWorkerVisible(agentId);
        String hash = idempotency.requestHash(teamId.toString(), agentId.toString(), role);
        return persistence.inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), ADD_MEMBER, hash, key);
                return tx.teams().findActiveMember(teamId, agentId)
                        .orElseThrow(() -> new IllegalStateException("idempotent team member is missing"));
            }
            tx.teams().findById(teamId).orElseThrow(() -> new ResourceNotFoundException("team", teamId));
            tx.agents().findById(agentId).orElseThrow(() -> new ResourceNotFoundException("agent", agentId));
            TeamMemberRecord member = new TeamMemberRecord(UUID.randomUUID(), teamId, agentId, role,
                    "ACTIVE", now, now, 0);
            IdempotencyKeyRecord record = idempotencyRecord(key, ADD_MEMBER, hash, member.id(), now);
            if (!tx.idempotencyKeys().insertIfAbsent(record)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, ADD_MEMBER, hash, key);
                return tx.teams().findActiveMember(teamId, agentId)
                        .orElseThrow(() -> new IllegalStateException("idempotent team member is missing"));
            }
            tx.teams().insertMember(member);
            return tx.teams().findActiveMember(teamId, agentId).orElse(member);
        });
    }

    public TeamRecord get(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId");
        requireVisible(teamId);
        return persistence.inTransaction(tx -> tx.teams().findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("team", teamId)));
    }

    public List<TeamRecord> list() {
        return persistence.inTransaction(tx -> tx.teams().findAll()).stream()
                .filter(team -> isVisible(team.id())).toList();
    }

    public List<TeamMemberRecord> members(UUID teamId) {
        get(teamId);
        return persistence.inTransaction(tx -> tx.teams().allMembers(teamId));
    }

    public TeamPolicyRecord policy(UUID teamId) {
        get(teamId);
        return persistence.inTransaction(tx -> tx.teams().findPolicy(teamId)
                .orElseThrow(() -> new IllegalStateException("team policy is missing")));
    }

    public TeamPolicyRecord updatePolicy(UUID teamId, int maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities, long expectedVersion, Instant now) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    public TeamPolicyRecord updatePolicy(UUID teamId, int maxConcurrentTasks, boolean requireHumanApproval,
            List<String> allowedRuntimes, List<String> requiredCapabilities, long expectedVersion, Instant now,
            String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        get(teamId);
        TeamPolicyRecord next = new TeamPolicyRecord(teamId, maxConcurrentTasks, requireHumanApproval,
                allowedRuntimes, requiredCapabilities, now, expectedVersion);
        String hash = idempotency.requestHash(teamId.toString(), Integer.toString(maxConcurrentTasks),
                Boolean.toString(requireHumanApproval), allowedRuntimes.toString(), requiredCapabilities.toString(),
                Long.toString(expectedVersion));
        return persistence.inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), UPDATE_POLICY, hash, key);
                return tx.teams().findPolicy(teamId).orElseThrow(() -> new IllegalStateException("team policy is missing"));
            }
            IdempotencyKeyRecord record = idempotencyRecord(key, UPDATE_POLICY, hash, teamId, now);
            if (!tx.idempotencyKeys().insertIfAbsent(record)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, UPDATE_POLICY, hash, key);
                return tx.teams().findPolicy(teamId).orElseThrow(() -> new IllegalStateException("team policy is missing"));
            }
            return tx.teams().updatePolicy(next, expectedVersion);
        });
    }

    public void removeMember(UUID teamId, UUID agentId, Instant now) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    public void removeMember(UUID teamId, UUID agentId, Instant now, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        get(teamId);
        String hash = idempotency.requestHash(teamId.toString(), agentId.toString());
        persistence.inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), REMOVE_MEMBER, hash, key);
                return null;
            }
            IdempotencyKeyRecord record = idempotencyRecord(key, REMOVE_MEMBER, hash, teamId, now);
            if (!tx.idempotencyKeys().insertIfAbsent(record)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, REMOVE_MEMBER, hash, key);
                return null;
            }
            tx.teams().deactivateMember(teamId, agentId, now);
            return null;
        });
    }

    public void delete(UUID teamId, Instant now) {
        throw new IllegalArgumentException("Idempotency-Key is required");
    }

    public void delete(UUID teamId, Instant now, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        Objects.requireNonNull(now, "now");
        get(teamId);
        String hash = idempotency.requestHash(teamId.toString());
        persistence.inTransaction(tx -> {
            var existing = tx.idempotencyKeys().findByKey(key);
            if (existing.isPresent()) {
                assertIdempotency(existing.get(), DELETE_TEAM, hash, key);
                return null;
            }
            IdempotencyKeyRecord record = idempotencyRecord(key, DELETE_TEAM, hash, teamId, now);
            if (!tx.idempotencyKeys().insertIfAbsent(record)) {
                IdempotencyKeyRecord winner = tx.idempotencyKeys().findByKey(key)
                        .orElseThrow(() -> new IllegalStateException("idempotency key disappeared"));
                assertIdempotency(winner, DELETE_TEAM, hash, key);
                return null;
            }
            tx.teams().markDeleted(teamId, now);
            return null;
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

    private boolean isVisible(UUID resourceId) {
        try {
            requireVisible(resourceId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void requireWorkerVisible(UUID resourceId) {
        if (resourceScopes != null) {
            resourceScopes.requireVisible("WORKER", resourceId);
        }
    }

    private String requireKey(String value) {
        if (idempotency == null) throw new IllegalStateException("team idempotency is not configured");
        return idempotency.requireKey(value);
    }

    private static IdempotencyKeyRecord idempotencyRecord(String key, String operation, String hash, UUID resourceId,
            Instant now) {
        return new IdempotencyKeyRecord(UUID.randomUUID(), key, operation, hash, "team", resourceId, "{}", now, now, 0);
    }

    private static void assertIdempotency(IdempotencyKeyRecord existing, String operation, String hash, String key) {
        if (!operation.equals(existing.operation()) || !hash.equals(existing.requestHash())) {
            throw new IdempotencyConflictException(key, operation);
        }
    }
}
