package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public DashboardAlertController(UsageQueryService usage) {
        this(usage, new DashboardAlertService(), new LoggingDashboardAlertNotificationPort(), null, null);
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts) {
        this(usage, alerts, new LoggingDashboardAlertNotificationPort(), null, null);
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications) {
        this(usage, alerts, notifications, null, null);
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications, DashboardAlertDeliveryService delivery) {
        this(usage, alerts, notifications, delivery, null);
    }

    @Autowired
    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications, DashboardAlertDeliveryService delivery,
            DashboardAlertEventRepository events) {
        this.usage = usage;
        this.alerts = alerts;
        this.notifications = notifications;
        this.delivery = delivery;
        this.events = events;
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
        return new Evaluation(result.from(), result.to(), alerts.evaluate(summary));
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

    public record NotificationResponse(String channel, boolean delivered, int alertCount) { }
    private record Evaluation(Instant from, Instant to, List<DashboardAlertService.Alert> alerts) { }
}
