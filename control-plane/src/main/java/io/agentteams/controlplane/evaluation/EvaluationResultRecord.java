package io.agentteams.controlplane.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record EvaluationResultRecord(UUID id, UUID evaluationRunId, String metric, BigDecimal score,
        BigDecimal threshold, Status status, List<String> evidenceRefs, String findingSummary, Instant createdAt) {
    public enum Status { PASSED, FAILED, INCONCLUSIVE }

    public EvaluationResultRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evaluationRunId, "evaluationRunId");
        metric = required(metric, "metric");
        score = bounded(score, "score");
        threshold = bounded(threshold, "threshold");
        Objects.requireNonNull(status, "status");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        evidenceRefs.forEach(EvaluationResultRecord::validateEvidenceRef);
        findingSummary = findingSummary == null ? "" : findingSummary.trim();
        rejectSensitive(findingSummary, "findingSummary");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static BigDecimal bounded(BigDecimal value, String field) {
        if (value != null && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static void validateEvidenceRef(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("evidenceRefs must not contain blank values");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("token") || normalized.contains("password") || normalized.contains("cookie")
                || normalized.contains("secret") || normalized.contains("authorization")
                || normalized.contains("x-amz-") || normalized.contains("presign") || normalized.contains("?")) {
            throw new IllegalArgumentException("evidenceRefs must contain safe durable references");
        }
    }

    private static void rejectSensitive(String value, String field) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("token") || normalized.contains("password") || normalized.contains("cookie")
                || normalized.contains("secret") || normalized.contains("authorization")) {
            throw new IllegalArgumentException(field + " contains restricted material");
        }
    }
}
