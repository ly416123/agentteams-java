package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EvaluationReportRecord(UUID id, UUID evaluationRunId, BigDecimal aggregateScore,
        JsonNode dimensionScores, EvaluationRunRecord.Status evaluationStatus, boolean taskSuccess,
        GateStatus executionGateStatus, GateStatus resultGateStatus, Instant createdAt) {
    public enum GateStatus { PASSED, FAILED, INCONCLUSIVE }

    /** Keeps reports created before independent gates source-compatible and explicitly inconclusive. */
    public EvaluationReportRecord(UUID id, UUID evaluationRunId, BigDecimal aggregateScore,
            JsonNode dimensionScores, EvaluationRunRecord.Status evaluationStatus, boolean taskSuccess,
            Instant createdAt) {
        this(id, evaluationRunId, aggregateScore, dimensionScores, evaluationStatus, taskSuccess,
                GateStatus.INCONCLUSIVE, GateStatus.INCONCLUSIVE, createdAt);
    }

    public EvaluationReportRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evaluationRunId, "evaluationRunId");
        if (aggregateScore != null && (aggregateScore.compareTo(BigDecimal.ZERO) < 0
                || aggregateScore.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("aggregateScore must be between 0 and 1");
        }
        if (dimensionScores == null || !dimensionScores.isObject()) {
            throw new IllegalArgumentException("dimensionScores must be a JSON object");
        }
        Objects.requireNonNull(evaluationStatus, "evaluationStatus");
        Objects.requireNonNull(executionGateStatus, "executionGateStatus");
        Objects.requireNonNull(resultGateStatus, "resultGateStatus");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
