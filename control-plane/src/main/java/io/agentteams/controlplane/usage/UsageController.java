package io.agentteams.controlplane.usage;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public final class UsageController {

    private final UsageQueryService service;

    public UsageController(UsageQueryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "groupBy", required = false) String groupBy,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UsageQueryService.UsageSummary summary = groupBy == null && limit == null
                ? service.summarize(from, to)
                : service.summarize(from, to, groupBy, limit);
        return UsageSummaryResponse.from(summary, UsageQueryService.GroupBy.parse(groupBy));
    }

    public record UsageSummaryResponse(Instant from, Instant to, long calls, long failures,
            long promptTokens, long completionTokens, double costUsd, double averageLatencyMillis,
            List<ProviderModelUsageResponse> byProviderModel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String groupBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<UsageGroupResponse> groups) {

        static UsageSummaryResponse from(UsageQueryService.UsageSummary summary, UsageQueryService.GroupBy groupBy) {
            UsageQueryService.UsageTotals totals = summary.totals();
            boolean legacyGrouping = groupBy == UsageQueryService.GroupBy.PROVIDER_MODEL;
            return new UsageSummaryResponse(summary.from(), summary.to(), totals.calls(), totals.failures(),
                    totals.promptTokens(), totals.completionTokens(), totals.costUsd(), totals.averageLatencyMillis(),
                    legacyGrouping ? summary.groups().stream().map(ProviderModelUsageResponse::from).toList() : List.of(),
                    legacyGrouping ? null : groupBy.name().toLowerCase(java.util.Locale.ROOT),
                    legacyGrouping ? null : summary.groups().stream().map(UsageGroupResponse::from).toList());
        }
    }

    public record ProviderModelUsageResponse(String provider, String model, long calls, long failures,
            long promptTokens, long completionTokens, double costUsd, double averageLatencyMillis) {

        static ProviderModelUsageResponse from(UsageQueryService.UsageGroup group) {
            return new ProviderModelUsageResponse(group.provider(), group.model(), group.calls(), group.failures(),
                    group.promptTokens(), group.completionTokens(), group.costUsd(), group.averageLatencyMillis());
        }
    }

    public record UsageGroupResponse(String provider, String model, String status, long calls, long failures,
            long promptTokens, long completionTokens, double costUsd, double averageLatencyMillis,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dimension,
            @JsonInclude(JsonInclude.Include.NON_NULL) String dimensionValue) {

        public UsageGroupResponse(String provider, String model, String status, long calls, long failures,
                long promptTokens, long completionTokens, double costUsd, double averageLatencyMillis) {
            this(provider, model, status, calls, failures, promptTokens, completionTokens, costUsd,
                    averageLatencyMillis, null, null);
        }

        static UsageGroupResponse from(UsageQueryService.UsageGroup group) {
            return new UsageGroupResponse(group.provider(), group.model(), group.status(), group.calls(),
                    group.failures(), group.promptTokens(), group.completionTokens(), group.costUsd(),
                    group.averageLatencyMillis(), group.dimension(), group.dimensionValue());
        }
    }
}
