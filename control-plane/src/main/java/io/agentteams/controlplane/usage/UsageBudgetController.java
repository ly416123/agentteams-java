package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped budget policy and evaluation read API. */
@RestController
@RequestMapping("/api/v1/usage/budgets")
public final class UsageBudgetController {
    private final UsageBudgetService service;

    public UsageBudgetController(UsageBudgetService service) {
        this.service = service;
    }

    @PutMapping("/{policyId}")
    public ResponseEntity<BudgetPolicyResponse> upsert(@PathVariable UUID policyId,
            @RequestBody PolicyRequest request) {
        requireRequest(request);
        AuthorizationService.Scope scope = callerScope();
        UsageBudgetService.PolicyInput input = request.toInput();
        return ResponseEntity.ok(BudgetPolicyResponse.from(service.upsert(policyId, scope, input)));
    }

    @GetMapping
    public List<BudgetPolicyResponse> list() {
        AuthorizationService.Scope scope = callerScope();
        return service.list(scope).stream().map(BudgetPolicyResponse::from).toList();
    }

    @GetMapping("/{policyId}/evaluations")
    public ResponseEntity<?> evaluations(@PathVariable UUID policyId,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(new ValidationError("limit must be between 1 and 100"));
        }
        AuthorizationService.Scope scope = callerScope();
        return ResponseEntity.ok(service.evaluations(policyId, scope, limit).stream()
                .map(BudgetEvaluationResponse::from).toList());
    }

    private static AuthorizationService.Scope callerScope() {
        return PrincipalContext.current().map(principal -> principal.scope())
                .orElseThrow(() -> new AuthorizationException("authenticated project scope is required"));
    }

    private static void requireRequest(PolicyRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
    }

    public record PolicyRequest(String currency, Long periodSeconds, BigDecimal softThreshold,
            BigDecimal hardThreshold, Long forecastWindowSeconds, UsageBudgetPolicy.Status status,
            Long expectedVersion, String tenantId, String projectId) {
        UsageBudgetService.PolicyInput toInput() {
            if (periodSeconds == null || periodSeconds < 1) throw new IllegalArgumentException("periodSeconds is required");
            if (forecastWindowSeconds == null || forecastWindowSeconds < 1) {
                throw new IllegalArgumentException("forecastWindowSeconds is required");
            }
            return new UsageBudgetService.PolicyInput(currency, Duration.ofSeconds(periodSeconds), softThreshold,
                    hardThreshold, Duration.ofSeconds(forecastWindowSeconds),
                    status == null ? UsageBudgetPolicy.Status.ACTIVE : status,
                    expectedVersion == null ? 0 : expectedVersion);
        }
    }

    public record BudgetPolicyResponse(UUID id, String tenantId, String projectId, String currency,
            long periodSeconds, BigDecimal softThreshold, BigDecimal hardThreshold, long forecastWindowSeconds,
            UsageBudgetPolicy.Status status, Instant createdAt, Instant updatedAt, long version) {
        static BudgetPolicyResponse from(UsageBudgetPolicy policy) {
            return new BudgetPolicyResponse(policy.id(), policy.tenantId(), policy.projectId(), policy.currency(),
                    policy.period().toSeconds(), policy.softThreshold(), policy.hardThreshold(),
                    policy.forecastWindow().toSeconds(), policy.status(), policy.createdAt(), policy.updatedAt(),
                    policy.version());
        }
    }

    public record BudgetEvaluationResponse(UUID id, UUID policyId, Instant windowStart, Instant windowEnd,
            BigDecimal actualCost, BigDecimal forecastCost, UsageBudgetEvaluation.Status status,
            Instant evaluatedAt) {
        static BudgetEvaluationResponse from(UsageBudgetEvaluation evaluation) {
            return new BudgetEvaluationResponse(evaluation.id(), evaluation.policyId(), evaluation.windowStart(),
                    evaluation.windowEnd(), evaluation.actualCost(), evaluation.forecastCost(), evaluation.status(),
                    evaluation.evaluatedAt());
        }
    }

    private record ValidationError(String message) { }
}
