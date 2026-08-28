package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for project-scoped budget policies and evaluations. */
public interface UsageBudgetRepository {
    UsageBudgetPolicy insert(UsageBudgetPolicy policy);
    UsageBudgetPolicy update(UsageBudgetPolicy policy, long expectedVersion) throws OptimisticLockFailure;
    Optional<UsageBudgetPolicy> findById(UUID id, String tenantId, String projectId);
    List<UsageBudgetPolicy> findAll(String tenantId, String projectId);
    boolean insertEvaluationIfAbsent(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation, String fingerprint);
    List<UsageBudgetEvaluation> findEvaluations(UUID policyId, String tenantId, String projectId, int limit);
}
