package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stable dashboard read model backed by the Usage API's canonical aggregation. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardSummaryController {
    private final UsageQueryService usage;

    public DashboardSummaryController(UsageQueryService usage) {
        this.usage = usage;
    }

    @GetMapping("/summary")
    public DashboardSummary summary(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        UsageQueryService.UsageSummary result = usage.summarize(from, to);
        UsageQueryService.UsageTotals totals = result.totals();
        return new DashboardSummary(result.from(), result.to(), totals.calls(), totals.failures(),
                totals.promptTokens(), totals.completionTokens(), totals.averageLatencyMillis(),
                result.groups().stream().map(DashboardGroup::from).toList());
    }

    public record DashboardSummary(Instant from, Instant to, long calls, long failures, long promptTokens,
            long completionTokens, double averageLatencyMillis, List<DashboardGroup> byProviderModel) {
        public DashboardSummary {
            byProviderModel = List.copyOf(byProviderModel);
        }
    }

    public record DashboardGroup(String provider, String model, long calls, long failures, long promptTokens,
            long completionTokens, double averageLatencyMillis) {
        static DashboardGroup from(UsageQueryService.UsageGroup group) {
            return new DashboardGroup(group.provider(), group.model(), group.calls(), group.failures(),
                    group.promptTokens(), group.completionTokens(), group.averageLatencyMillis());
        }
    }
}
