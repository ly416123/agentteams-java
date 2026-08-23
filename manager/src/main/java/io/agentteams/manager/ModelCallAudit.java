package io.agentteams.manager;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable, content-free record of one model call. */
public record ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
        String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt,
        String tenantId, String projectId, double costUsd, CostStatus costStatus, Dimensions dimensions) {
    public enum Outcome { SUCCESS, FAILURE }

    public enum CostStatus { ESTIMATED, UNPRICED, NOT_APPLICABLE }

    /** Nullable operational identifiers used for dashboard grouping; never content or credentials. */
    public record Dimensions(String workerId, String taskId, String teamId, String toolId,
            String quotaId, String quotaDimension) {
        public Dimensions {
            requireOptionalText(workerId, "workerId");
            requireOptionalText(taskId, "taskId");
            requireOptionalText(teamId, "teamId");
            requireOptionalText(toolId, "toolId");
            requireOptionalText(quotaId, "quotaId");
            requireOptionalText(quotaDimension, "quotaDimension");
        }
    }

    public record TokenUsage(long promptTokens, long completionTokens) {
        public TokenUsage {
            if (promptTokens < 0 || completionTokens < 0) {
                throw new IllegalArgumentException("token usage must not be negative");
            }
        }
    }

    public ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
            String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt) {
        this(provider, model, latency, tokenUsage, requestHash, responseHash, outcome, errorCategory, occurredAt,
                null, null, 0, outcome == Outcome.FAILURE ? CostStatus.NOT_APPLICABLE : CostStatus.UNPRICED,
                null);
    }

    /** Compatibility constructor for the scope/cost shape introduced before cost status was added. */
    public ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
            String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt,
            String tenantId, String projectId, double costUsd) {
        this(provider, model, latency, tokenUsage, requestHash, responseHash, outcome, errorCategory, occurredAt,
                tenantId, projectId, costUsd,
                outcome == Outcome.FAILURE ? CostStatus.NOT_APPLICABLE
                        : costUsd > 0 ? CostStatus.ESTIMATED : CostStatus.UNPRICED,
                null);
    }

    /** Compatibility constructor for the scope/cost/status shape preceding dimensions. */
    public ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
            String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt,
            String tenantId, String projectId, double costUsd, CostStatus costStatus) {
        this(provider, model, latency, tokenUsage, requestHash, responseHash, outcome, errorCategory, occurredAt,
                tenantId, projectId, costUsd, costStatus, null);
    }

    /** Compatibility constructor for callers that only need to attach dimensions. */
    public ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
            String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt,
            Dimensions dimensions) {
        this(provider, model, latency, tokenUsage, requestHash, responseHash, outcome, errorCategory, occurredAt,
                null, null, 0, outcome == Outcome.FAILURE ? CostStatus.NOT_APPLICABLE : CostStatus.UNPRICED,
                dimensions);
    }

    /** Scope/cost constructor with explicit operational dimensions. */
    public ModelCallAudit(String provider, String model, Duration latency, TokenUsage tokenUsage,
            String requestHash, String responseHash, Outcome outcome, String errorCategory, Instant occurredAt,
            String tenantId, String projectId, double costUsd, Dimensions dimensions) {
        this(provider, model, latency, tokenUsage, requestHash, responseHash, outcome, errorCategory, occurredAt,
                tenantId, projectId, costUsd,
                outcome == Outcome.FAILURE ? CostStatus.NOT_APPLICABLE
                        : costUsd > 0 ? CostStatus.ESTIMATED : CostStatus.UNPRICED,
                dimensions);
    }

    public ModelCallAudit {
        requireText(provider, "provider");
        requireText(model, "model");
        Objects.requireNonNull(latency, "latency");
        if (latency.isNegative()) throw new IllegalArgumentException("latency must not be negative");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        requireText(requestHash, "requestHash");
        if (responseHash != null && responseHash.isBlank()) {
            throw new IllegalArgumentException("responseHash must not be blank when present");
        }
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(costStatus, "costStatus");
        if ((tenantId == null) != (projectId == null)) {
            throw new IllegalArgumentException("tenantId and projectId must be supplied together");
        }
        if (tenantId != null && (tenantId.isBlank() || projectId.isBlank())) {
            throw new IllegalArgumentException("tenantId and projectId must not be blank");
        }
        if (costUsd < 0 || !Double.isFinite(costUsd)) {
            throw new IllegalArgumentException("costUsd must be finite and non-negative");
        }
        if (costStatus != CostStatus.ESTIMATED && costUsd != 0) {
            throw new IllegalArgumentException("only estimated cost may be non-zero");
        }
        if (outcome == Outcome.FAILURE && costStatus != CostStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("failed calls must not have a cost status");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
