package io.agentteams.controlplane.usage;

import java.time.Instant;
import java.util.List;
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
            @RequestParam(name = "to", required = false) Instant to) {
        return UsageSummaryResponse.from(service.summarize(from, to));
    }

    public record UsageSummaryResponse(Instant from, Instant to, long calls, long failures,
            long promptTokens, long completionTokens, double averageLatencyMillis,
            List<ProviderModelUsageResponse> byProviderModel) {

        static UsageSummaryResponse from(UsageQueryService.UsageSummary summary) {
            UsageQueryService.UsageTotals totals = summary.totals();
            return new UsageSummaryResponse(summary.from(), summary.to(), totals.calls(), totals.failures(),
                    totals.promptTokens(), totals.completionTokens(), totals.averageLatencyMillis(),
                    summary.groups().stream().map(ProviderModelUsageResponse::from).toList());
        }
    }

    public record ProviderModelUsageResponse(String provider, String model, long calls, long failures,
            long promptTokens, long completionTokens, double averageLatencyMillis) {

        static ProviderModelUsageResponse from(UsageQueryService.UsageGroup group) {
            return new ProviderModelUsageResponse(group.provider(), group.model(), group.calls(), group.failures(),
                    group.promptTokens(), group.completionTokens(), group.averageLatencyMillis());
        }
    }
}
