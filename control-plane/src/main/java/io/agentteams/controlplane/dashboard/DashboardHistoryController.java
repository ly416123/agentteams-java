package io.agentteams.controlplane.dashboard;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Historical usage series used by dashboard charts; data stays project scoped. */
@RestController
@RequestMapping("/api/v1/dashboard")
public final class DashboardHistoryController {
    private final UsageQueryService usage;

    public DashboardHistoryController(UsageQueryService usage) {
        this.usage = usage;
    }

    @GetMapping("/timeseries")
    public DashboardTimeseries timeseries(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "bucket", required = false) String bucket) {
        UsageQueryService.UsageRange range = UsageQueryService.UsageRange.resolve(from, to, Instant.now());
        return new DashboardTimeseries(range.from(), range.to(), bucket == null ? "hour" : bucket,
                usage.timeseries(from, to, bucket).stream().map(DashboardBucket::from).toList());
    }

    public record DashboardTimeseries(Instant from, Instant to, String bucket, List<DashboardBucket> points) {
        public DashboardTimeseries {
            points = List.copyOf(points);
        }
    }

    public record DashboardBucket(Instant bucket, long calls, long failures, long promptTokens,
            long completionTokens, double estimatedCostUsd, double averageLatencyMillis) {
        static DashboardBucket from(UsageQueryService.UsageBucket value) {
            return new DashboardBucket(value.bucket(), value.calls(), value.failures(), value.promptTokens(),
                    value.completionTokens(), value.costUsd(), value.averageLatencyMillis());
        }
    }
}
