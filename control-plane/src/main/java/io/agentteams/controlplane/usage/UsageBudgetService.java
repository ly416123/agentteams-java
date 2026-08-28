package io.agentteams.controlplane.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Budget policy lifecycle, project-scoped observations, and pure forecast rules. */
@Service
public final class UsageBudgetService {
    private static final int CALCULATION_SCALE = 8;
    private final UsageBudgetRepository repository;
    private final UsageQueryService usage;
    private final Clock clock;

    /** Constructor used by pure rule tests; persistence operations are unavailable. */
    public UsageBudgetService() {
        this.repository = null;
        this.usage = null;
        this.clock = Clock.systemUTC();
    }

    @Autowired
    public UsageBudgetService(UsageBudgetRepository repository, UsageQueryService usage, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UsageBudgetEvaluation evaluate(UsageBudgetPolicy policy, CostObservation observation, Instant evaluatedAt) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Instant windowEnd = evaluatedAt;
        long periodSeconds = policy.period().toSeconds();
        Instant windowStart = alignedWindowStart(windowEnd, periodSeconds);
        if (observation.unpricedCalls() > 0) {
            return evaluation(policy, windowStart, windowEnd, null, null,
                    UsageBudgetEvaluation.Status.UNPRICED, evaluatedAt);
        }
        if (observation.pricedCalls() == 0) {
            return evaluation(policy, windowStart, windowEnd, null, null,
                    UsageBudgetEvaluation.Status.INSUFFICIENT_DATA, evaluatedAt);
        }
        BigDecimal actual = observation.actualCost();
        if (observation.observed().compareTo(policy.forecastWindow()) < 0) {
            return evaluation(policy, windowStart, windowEnd, actual, null,
                    UsageBudgetEvaluation.Status.INSUFFICIENT_DATA, evaluatedAt);
        }
        BigDecimal forecast = actual.multiply(BigDecimal.valueOf(periodSeconds))
                .divide(BigDecimal.valueOf(observation.observed().toSeconds()), CALCULATION_SCALE, RoundingMode.HALF_UP);
        return evaluation(policy, windowStart, windowEnd, actual, forecast, thresholdStatus(policy, forecast), evaluatedAt);
    }

    public UsageBudgetPolicy upsert(UUID policyId, AuthorizationService.Scope scope, PolicyInput input) {
        requirePersistence();
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(input, "input");
        Instant now = clock.instant();
        UsageBudgetPolicy candidate = new UsageBudgetPolicy(policyId, scope.tenant(), scope.project(), input.currency(),
                input.period(), input.softThreshold(), input.hardThreshold(), input.forecastWindow(), input.status(),
                now, now, input.expectedVersion());
        var existing = repository.findById(policyId, scope.tenant(), scope.project());
        if (existing.isPresent()) {
            return repository.update(candidate, input.expectedVersion());
        }
        if (input.expectedVersion() != 0) {
            throw new OptimisticLockFailure("usage_budget_policy", policyId, input.expectedVersion(), -1);
        }
        return repository.insert(candidate);
    }

    public List<UsageBudgetPolicy> list(AuthorizationService.Scope scope) {
        requirePersistence();
        Objects.requireNonNull(scope, "scope");
        return repository.findAll(scope.tenant(), scope.project());
    }

    public List<UsageBudgetEvaluation> evaluations(UUID policyId, AuthorizationService.Scope scope, int limit) {
        requirePersistence();
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(scope, "scope");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        requirePolicy(policyId, scope);
        return repository.findEvaluations(policyId, scope.tenant(), scope.project(), limit);
    }

    /** Computes and persists one current-window evaluation for an existing policy. */
    public UsageBudgetEvaluation evaluateCurrent(UUID policyId, AuthorizationService.Scope scope) {
        requirePersistence();
        return evaluateCurrent(requirePolicy(policyId, scope));
    }

    /** Computes and persists one current-window evaluation for a scheduler-owned policy. */
    public UsageBudgetEvaluation evaluateCurrent(UsageBudgetPolicy policy) {
        requirePersistence();
        Objects.requireNonNull(policy, "policy");
        Instant now = clock.instant();
        Instant windowStart = alignedWindowStart(now, policy.period().toSeconds());
        UsageQueryService.UsageSummary summary = usage.summarizeForScope(policy.tenantId(), policy.projectId(),
                windowStart, now);
        UsageQueryService.UsageTotals totals = summary.totals();
        long observedSeconds = Math.max(1, Duration.between(summary.from(), summary.to()).toSeconds());
        CostObservation observation = new CostObservation(BigDecimal.valueOf(totals.costUsd()), totals.pricedCalls(),
                totals.unpricedCalls(), Duration.ofSeconds(observedSeconds));
        UsageBudgetEvaluation evaluation = evaluate(policy, observation, now);
        repository.upsertEvaluation(policy, evaluation);
        if (isNotifiable(evaluation.status())) {
            repository.insertEventIfAbsent(UsageBudgetEvent.pending(fingerprint(policy, evaluation), policy, evaluation, now));
        }
        return evaluation;
    }

    private UsageBudgetPolicy requirePolicy(UUID policyId, AuthorizationService.Scope scope) {
        return repository.findById(policyId, scope.tenant(), scope.project())
                .orElseThrow(() -> new ResourceNotFoundException("usage budget policy", policyId));
    }

    private void requirePersistence() {
        if (repository == null || usage == null) {
            throw new IllegalStateException("budget persistence is not configured");
        }
    }

    private static Instant alignedWindowStart(Instant end, long periodSeconds) {
        Instant start = end.minusSeconds(Math.floorMod(end.getEpochSecond(), periodSeconds));
        return start.equals(end) ? start.minusSeconds(periodSeconds) : start;
    }

    private static String fingerprint(UsageBudgetPolicy policy, UsageBudgetEvaluation evaluation) {
        String input = policy.id() + "|" + policy.version() + "|" + evaluation.windowStart() + "|"
                + evaluation.status();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isNotifiable(UsageBudgetEvaluation.Status status) {
        return status == UsageBudgetEvaluation.Status.SOFT_LIMIT || status == UsageBudgetEvaluation.Status.HARD_LIMIT;
    }

    public record PolicyInput(String currency, Duration period, BigDecimal softThreshold, BigDecimal hardThreshold,
            Duration forecastWindow, UsageBudgetPolicy.Status status, long expectedVersion) {
        public PolicyInput {
            if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(softThreshold, "softThreshold");
            Objects.requireNonNull(hardThreshold, "hardThreshold");
            Objects.requireNonNull(forecastWindow, "forecastWindow");
            Objects.requireNonNull(status, "status");
        }
    }

    private static UsageBudgetEvaluation.Status thresholdStatus(UsageBudgetPolicy policy, BigDecimal value) {
        if (value.compareTo(policy.hardThreshold()) >= 0) return UsageBudgetEvaluation.Status.HARD_LIMIT;
        if (value.compareTo(policy.softThreshold()) >= 0) return UsageBudgetEvaluation.Status.SOFT_LIMIT;
        return UsageBudgetEvaluation.Status.UNDER_BUDGET;
    }

    private static UsageBudgetEvaluation evaluation(UsageBudgetPolicy policy, Instant windowStart, Instant windowEnd,
            BigDecimal actual, BigDecimal forecast, UsageBudgetEvaluation.Status status, Instant evaluatedAt) {
        return new UsageBudgetEvaluation(UUID.randomUUID(), policy.id(), windowStart, windowEnd, actual, forecast,
                status, evaluatedAt);
    }

    public record CostObservation(BigDecimal actualCost, long pricedCalls, long unpricedCalls, Duration observed) {
        public CostObservation {
            if (pricedCalls < 0 || unpricedCalls < 0) throw new IllegalArgumentException("call counts must not be negative");
            actualCost = Objects.requireNonNull(actualCost, "actualCost");
            if (actualCost.signum() < 0) throw new IllegalArgumentException("actualCost must not be negative");
            observed = Objects.requireNonNull(observed, "observed");
            if (observed.isNegative() || observed.isZero()) throw new IllegalArgumentException("observed must be positive");
            if (pricedCalls == 0 && actualCost.signum() != 0) {
                throw new IllegalArgumentException("actualCost requires priced calls");
            }
        }
    }
}
