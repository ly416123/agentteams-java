package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
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
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "groupBy", required = false) String groupBy,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UsageQueryService.UsageSummary result = groupBy == null && limit == null
                ? usage.summarize(from, to) : usage.summarize(from, to, groupBy, limit);
        UsageQueryService.UsageTotals totals = result.totals();
        UsageQueryService.GroupBy grouping = UsageQueryService.GroupBy.parse(groupBy);
        boolean legacyGrouping = grouping == UsageQueryService.GroupBy.PROVIDER_MODEL;
        List<DashboardGroup> groups = result.groups().stream().map(DashboardGroup::from).toList();
        return new DashboardSummary(result.from(), result.to(), totals.calls(), totals.failures(),
                totals.promptTokens(), totals.completionTokens(), totals.costUsd(), totals.averageLatencyMillis(),
                legacyGrouping ? groups : List.of(),
                legacyGrouping ? null : grouping.name().toLowerCase(java.util.Locale.ROOT),
                legacyGrouping ? List.of() : groups);
    }

    public record DashboardSummary(Instant from, Instant to, long calls, long failures, long promptTokens,
            long completionTokens, double estimatedCostUsd, double averageLatencyMillis, List<DashboardGroup> byProviderModel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String groupBy,
            List<DashboardGroup> groups) {
        public DashboardSummary(Instant from, Instant to, long calls, long failures, long promptTokens,
                long completionTokens, double estimatedCostUsd, double averageLatencyMillis,
                List<DashboardGroup> byProviderModel) {
            this(from, to, calls, failures, promptTokens, completionTokens, estimatedCostUsd,
                    averageLatencyMillis, byProviderModel, null, List.of());
        }

        public DashboardSummary {
            byProviderModel = List.copyOf(byProviderModel);
            groups = List.copyOf(groups);
        }
    }

    public record DashboardGroup(String provider, String model, long calls, long failures, long promptTokens,
            long completionTokens, double estimatedCostUsd, double averageLatencyMillis,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dimension,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dimensionValue) {
        public DashboardGroup(String provider, String model, long calls, long failures, long promptTokens,
                long completionTokens, double estimatedCostUsd, double averageLatencyMillis) {
            this(provider, model, calls, failures, promptTokens, completionTokens, estimatedCostUsd,
                    averageLatencyMillis, null, null);
        }

        static DashboardGroup from(UsageQueryService.UsageGroup group) {
            return new DashboardGroup(group.provider(), group.model(), group.calls(), group.failures(),
                    group.promptTokens(), group.completionTokens(), group.costUsd(), group.averageLatencyMillis(),
                    group.dimension(), group.dimensionValue());
        }
    }
}
