package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped dashboard alert-rule configuration; global defaults are never writable here. */
@RestController
@RequestMapping("/api/v1/dashboard/alert-rules")
public final class DashboardAlertRuleManagementController {
    private static final AuditRecorder NOOP_AUDIT = event -> { };
    private final DashboardAlertRuleRepository repository;
    private final AuditRecorder auditRecorder;

    public DashboardAlertRuleManagementController(DashboardAlertRuleRepository repository) {
        this(repository, NOOP_AUDIT);
    }

    @Autowired
    public DashboardAlertRuleManagementController(DashboardAlertRuleRepository repository, AuditRecorder auditRecorder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    }

    @GetMapping
    public List<DashboardAlertRule> list() {
        var scope = callerScope();
        return repository.findForScope(scope.tenant(), scope.project());
    }

    @PutMapping("/{rule}")
    public DashboardAlertRule update(@PathVariable String rule, @RequestBody RuleRequest request) {
        if (request == null || request.severity() == null || request.threshold() == null
                || request.enabled() == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("severity, threshold, enabled and expectedVersion are required");
        }
        DashboardAlertRule candidate = new DashboardAlertRule(rule, request.severity(), request.threshold(),
                request.enabled());
        if (!List.of("FAILURE_RATE", "AVERAGE_LATENCY", "COST").contains(candidate.rule())) {
            throw new IllegalArgumentException("unsupported alert rule");
        }
        var scope = callerScope();
        DashboardAlertRule updated = repository.saveForScope(scope.tenant(), scope.project(), candidate,
                request.expectedVersion());
        auditRecorder.record(new AuditEvent(java.util.UUID.randomUUID(),
                PrincipalContext.actorOr("unknown"), "DASHBOARD_ALERT_RULE_UPDATED", "dashboard_alert_rule",
                candidate.rule(), Map.of("tenantId", scope.tenant(), "projectId", scope.project(),
                        "version", Long.toString(updated.version())), Instant.now()));
        return updated;
    }

    private static io.agentteams.controlplane.security.AuthorizationService.Scope callerScope() {
        return PrincipalContext.current().map(principal -> principal.scope())
                .orElseThrow(() -> new AuthorizationException("authenticated project scope is required"));
    }

    public record RuleRequest(String severity, Double threshold, Boolean enabled, Long expectedVersion) { }
}
