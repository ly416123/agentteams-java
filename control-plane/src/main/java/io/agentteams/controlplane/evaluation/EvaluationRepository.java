package io.agentteams.controlplane.evaluation;

import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluationRepository {
    EvaluationSuiteRecord saveSuite(ExecutionContext context, EvaluationSuiteRecord suite);

    EvaluationCaseRecord saveCase(ExecutionContext context, EvaluationCaseRecord evaluationCase);

    EvaluationRunRecord createRun(ExecutionContext context, CreateEvaluationRun command);

    Optional<EvaluationRunRecord> findRun(ExecutionContext context, UUID runId);

    void saveResults(ExecutionContext context, UUID runId, List<EvaluationResultRecord> results);

    EvaluationReportRecord saveReport(ExecutionContext context, EvaluationReportRecord report);

    record CreateEvaluationRun(UUID id, UUID suiteId, UUID caseId, UUID taskId, UUID executionRunId,
            String evaluatorVersion, Instant createdAt) {
        public CreateEvaluationRun {
            if (id == null || suiteId == null || caseId == null || taskId == null || executionRunId == null) {
                throw new IllegalArgumentException("evaluation run identities are required");
            }
            if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
                throw new IllegalArgumentException("evaluatorVersion must not be blank");
            }
            if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
