package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard alert evaluation plus an explicit delivery trigger. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardAlertController {
    private final UsageQueryService usage;
    private final DashboardAlertService alerts;
    private final DashboardAlertNotificationPort notifications;
    private final DashboardAlertDeliveryService delivery;
    private final DashboardAlertEventRepository events;
    private final AuditRecorder audit;

    public DashboardAlertController(UsageQueryService usage) {
        this(usage, new DashboardAlertService(), new LoggingDashboardAlertNotificationPort(), null, null, event -> { });
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts) {
        this(usage, alerts, new LoggingDashboardAlertNotificationPort(), null, null, event -> { });
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications) {
        this(usage, alerts, notifications, null, null, event -> { });
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications, DashboardAlertDeliveryService delivery) {
        this(usage, alerts, notifications, delivery, null, event -> { });
    }

    @Autowired
    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications, DashboardAlertDeliveryService delivery,
            DashboardAlertEventRepository events, AuditRecorder audit) {
        this.usage = usage;
        this.alerts = alerts;
        this.notifications = notifications;
        this.delivery = delivery;
        this.events = events;
        this.audit = audit;
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications, DashboardAlertDeliveryService delivery,
            DashboardAlertEventRepository events) {
        this(usage, alerts, notifications, delivery, events, event -> { });
    }

    @GetMapping("/alerts")
    public List<DashboardAlertService.Alert> alerts(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        return evaluate(from, to).alerts();
    }

    @GetMapping("/alerts/events")
    public List<DashboardAlertEvent> events(@RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(name = "tenant", required = false) String tenant,
            @RequestParam(name = "project", required = false) String project) {
        if (events == null) return List.of();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return PrincipalContext.current()
                .map(principal -> principal.scope())
                .map(scope -> events.findRecent(scope.tenant(), scope.project(), limit))
                .orElseGet(() -> tenant != null && !tenant.isBlank() && project != null && !project.isBlank()
                        ? events.findRecent(tenant, project, limit) : List.of());
    }

    private Evaluation evaluate(Instant from, Instant to) {
        UsageQueryService.UsageSummary result = usage.summarize(from, to);
        UsageQueryService.UsageTotals totals = result.totals();
        var summary = new DashboardSummaryController.DashboardSummary(result.from(), result.to(), totals.calls(),
                totals.failures(), totals.promptTokens(), totals.completionTokens(), totals.costUsd(),
                totals.averageLatencyMillis(), result.groups().stream().map(DashboardSummaryController.DashboardGroup::from).toList());
        return PrincipalContext.current()
                .map(principal -> new Evaluation(result.from(), result.to(),
                        alerts.evaluate(summary, principal.scope().tenant(), principal.scope().project())))
                .orElseGet(() -> new Evaluation(result.from(), result.to(), alerts.evaluate(summary)));
    }

    @PostMapping("/alerts/notify")
    public NotificationResponse notify(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        if (delivery != null) {
            var principal = PrincipalContext.current();
            if (principal.isPresent()) {
                var scope = principal.get().scope();
                DashboardAlertDeliveryService.DeliveryResult result = delivery.deliver(scope.tenant(),
                        scope.project(), from, to);
                return new NotificationResponse("durable", result.failed() == 0,
                        result.delivered() + result.suppressed() + result.failed());
            }
        }
        Evaluation evaluated = evaluate(from, to);
        DashboardAlertNotificationPort.NotificationResult result = notifications.notify(
                new DashboardAlertNotificationPort.AlertNotification(
                        evaluated.from(), evaluated.to(), evaluated.alerts()));
        return new NotificationResponse(result.channel(), result.delivered(), result.alertCount());
    }

    @PostMapping("/alerts/events/{eventId}/retry")
    public DashboardAlertEvent retry(@PathVariable UUID eventId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        if (delivery == null) throw new IllegalStateException("durable alert delivery is unavailable");
        var principal = PrincipalContext.current()
                .orElseThrow(() -> new IllegalArgumentException("authenticated scope is required"));
        var scope = principal.scope();
        DashboardAlertEvent event = delivery.retryNow(scope.tenant(), scope.project(), eventId, idempotencyKey);
        audit.record(new AuditEvent(UUID.randomUUID(), PrincipalContext.actorOr("management-console"),
                "DASHBOARD_ALERT_RETRY_REQUESTED", "DASHBOARD_ALERT_EVENT", eventId.toString(),
                Map.of("tenantId", scope.tenant(), "projectId", scope.project(), "status", event.status().name()),
                Instant.now()));
        return event;
    }

    public record NotificationResponse(String channel, boolean delivered, int alertCount) { }
    private record Evaluation(Instant from, Instant to, List<DashboardAlertService.Alert> alerts) { }
}
