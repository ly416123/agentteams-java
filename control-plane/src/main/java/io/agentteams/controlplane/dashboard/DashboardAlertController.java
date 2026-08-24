package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
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

    public DashboardAlertController(UsageQueryService usage) {
        this(usage, new DashboardAlertService(), new LoggingDashboardAlertNotificationPort());
    }

    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts) {
        this(usage, alerts, new LoggingDashboardAlertNotificationPort());
    }

    @Autowired
    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts,
            DashboardAlertNotificationPort notifications) {
        this.usage = usage;
        this.alerts = alerts;
        this.notifications = notifications;
    }

    @GetMapping("/alerts")
    public List<DashboardAlertService.Alert> alerts(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        return evaluate(from, to).alerts();
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
        Evaluation evaluated = evaluate(from, to);
        DashboardAlertNotificationPort.NotificationResult result = notifications.notify(
                new DashboardAlertNotificationPort.AlertNotification(
                        evaluated.from(), evaluated.to(), evaluated.alerts()));
        return new NotificationResponse(result.channel(), result.delivered(), result.alertCount());
    }

    public record NotificationResponse(String channel, boolean delivered, int alertCount) { }
    private record Evaluation(Instant from, Instant to, List<DashboardAlertService.Alert> alerts) { }
}
