package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/** Persistence boundary for project-scoped budget policies and evaluations. */
public interface UsageBudgetRepository {
    UsageBudgetPolicy insert(UsageBudgetPolicy policy);
    UsageBudgetPolicy update(UsageBudgetPolicy policy, long expectedVersion) throws OptimisticLockFailure;
    Optional<UsageBudgetPolicy> findById(UUID id, String tenantId, String projectId);
    List<UsageBudgetPolicy> findAll(String tenantId, String projectId);
    List<UsageBudgetPolicy> findActive(int limit);
    void upsertEvaluation(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation);
    boolean insertEventIfAbsent(UsageBudgetEvent event);
    java.util.Optional<UsageBudgetEvent> claim(UsageBudgetEvent event, Instant now);
    List<UsageBudgetEvent> findPending(int limit);
    List<UsageBudgetEvent> findDue(Instant now, int limit);
    void markSent(UUID id, Instant at);
    void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at);
    boolean insertEvaluationIfAbsent(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation, String fingerprint);
    List<UsageBudgetEvaluation> findEvaluations(UUID policyId, String tenantId, String projectId, int limit);
}
