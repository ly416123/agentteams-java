package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only dashboard alert endpoint; notification delivery remains an adapter concern. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardAlertController {
    private final UsageQueryService usage;
    private final DashboardAlertService alerts;

    public DashboardAlertController(UsageQueryService usage) {
        this(usage, new DashboardAlertService());
    }

    @Autowired
    public DashboardAlertController(UsageQueryService usage, DashboardAlertService alerts) {
        this.usage = usage;
        this.alerts = alerts;
    }

    @GetMapping("/alerts")
    public List<DashboardAlertService.Alert> alerts(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        UsageQueryService.UsageSummary result = usage.summarize(from, to);
        UsageQueryService.UsageTotals totals = result.totals();
        var summary = new DashboardSummaryController.DashboardSummary(result.from(), result.to(), totals.calls(),
                totals.failures(), totals.promptTokens(), totals.completionTokens(), totals.costUsd(),
                totals.averageLatencyMillis(), result.groups().stream().map(DashboardSummaryController.DashboardGroup::from).toList());
        return alerts.evaluate(summary);
    }
}
