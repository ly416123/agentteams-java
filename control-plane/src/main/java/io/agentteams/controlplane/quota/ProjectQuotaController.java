package io.agentteams.controlplane.quota;

import io.agentteams.controlplane.security.AuthorizationService.Scope;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project quota read/configuration surface; authenticated requests always use token scope. */
@RestController
@RequestMapping("/api/v1/usage/quota")
public final class ProjectQuotaController {
    private final ProjectQuotaService service;

    public ProjectQuotaController(ProjectQuotaService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping
    public ResponseEntity<QuotaResponse> get(@RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestParam(name = "projectId", required = false) String projectId) {
        Scope scope = resolveScope(tenantId, projectId);
        return ResponseEntity.ok(QuotaResponse.from(scope, service.get(scope.tenant(), scope.project()).orElse(null)));
    }

    @PutMapping
    public ResponseEntity<QuotaResponse> put(@RequestBody QuotaRequest request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
        Scope scope = resolveScope(request.tenantId(), request.projectId());
        ProjectQuotaSnapshot snapshot = service.putPolicy(new ProjectQuotaPolicy(scope.tenant(), scope.project(),
                request.maxConcurrentCalls(), request.maxDailyCalls(), request.maxDailyTokens()));
        return ResponseEntity.ok(QuotaResponse.from(scope, snapshot));
    }

    private static Scope resolveScope(String tenantId, String projectId) {
        return PrincipalContext.current().map(principal -> principal.scope())
                .orElseGet(() -> new Scope(require(tenantId, "tenantId"), require(projectId, "projectId"), "quota-api"));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    public record QuotaRequest(String tenantId, String projectId, long maxConcurrentCalls,
            long maxDailyCalls, long maxDailyTokens) { }

    public record QuotaResponse(String tenantId, String projectId, boolean configured,
            long maxConcurrentCalls, long maxDailyCalls, long maxDailyTokens,
            long currentConcurrentCalls, long dailyCalls, long dailyTokens,
            long remainingConcurrentCalls, long remainingDailyCalls, long remainingDailyTokens,
            LocalDate usageDay) {
        static QuotaResponse from(Scope scope, ProjectQuotaSnapshot snapshot) {
            if (snapshot == null) {
                return new QuotaResponse(scope.tenant(), scope.project(), false, 0, 0, 0, 0, 0, 0,
                        -1, -1, -1, LocalDate.now());
            }
            return new QuotaResponse(snapshot.tenantId(), snapshot.projectId(), snapshot.configured(),
                    snapshot.maxConcurrentCalls(), snapshot.maxDailyCalls(), snapshot.maxDailyTokens(),
                    snapshot.currentConcurrentCalls(), snapshot.dailyCalls(), snapshot.dailyTokens(),
                    snapshot.remainingConcurrentCalls(), snapshot.remainingDailyCalls(),
                    snapshot.remainingDailyTokens(), snapshot.usageDay());
        }
    }
}
