package io.agentteams.controlplane.usage;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public final class UsageController {

    private final UsageQueryService service;
    private final AuditRecorder audit;

    public UsageController(UsageQueryService service) {
        this(service, event -> { });
    }

    @Autowired
    public UsageController(UsageQueryService service, AuditRecorder audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "groupBy", required = false) String groupBy,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "model", required = false) String model) {
        UsageQueryService.UsageFilters filters = new UsageQueryService.UsageFilters(taskId, provider, model);
        UsageQueryService.UsageSummary summary = offset != null
                ? service.summarize(from, to, groupBy, limit == null ? 100 : limit, filters, offset)
                : groupBy == null && limit == null && !filters.hasAny()
                ? service.summarize(from, to)
                : filters.hasAny() ? service.summarize(from, to, groupBy, limit, filters)
                : service.summarize(from, to, groupBy, limit);
        return UsageSummaryResponse.from(summary, UsageQueryService.GroupBy.parse(groupBy));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "groupBy", required = false, defaultValue = "provider_model") String groupBy,
            @RequestParam(name = "limit", required = false, defaultValue = "1000") Integer limit,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "model", required = false) String model) {
        UsageQueryService.UsageFilters filters = new UsageQueryService.UsageFilters(taskId, provider, model);
        UsageQueryService.UsageSummary summary = service.summarize(from, to, groupBy, limit, filters);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("groupBy", groupBy == null ? "provider_model" : groupBy);
        attributes.put("limit", Integer.toString(limit == null ? 1000 : limit));
        putIfPresent(attributes, "taskId", filters.taskId());
        putIfPresent(attributes, "provider", filters.provider());
        putIfPresent(attributes, "model", filters.model());
        PrincipalContext.current().ifPresent(principal -> {
            attributes.put("tenantId", principal.scope().tenant());
            attributes.put("projectId", principal.scope().project());
        });
        audit.record(new AuditEvent(UUID.randomUUID(), PrincipalContext.actorOr("management-console"),
                "USAGE_EXPORTED", "USAGE", "usage-export", attributes, Instant.now()));
        StringBuilder csv = new StringBuilder("provider,model,status,dimension,dimensionValue,calls,failures,promptTokens,completionTokens,costUsd,averageLatencyMillis\n");
        for (UsageQueryService.UsageGroup group : summary.groups()) {
            appendCsvRow(csv, group.provider(), group.model(), group.status(), group.dimension(),
                    group.dimensionValue(), Long.toString(group.calls()), Long.toString(group.failures()),
                    Long.toString(group.promptTokens()), Long.toString(group.completionTokens()),
                    Double.toString(group.costUsd()), Double.toString(group.averageLatencyMillis()));
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=usage.csv").body(csv.toString());
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null && !value.isBlank()) attributes.put(key, value);
    }

    @GetMapping("/dimensions/completeness")
    public UsageCompletenessResponse completeness(@RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to) {
        return UsageCompletenessResponse.from(service.completeness(from, to));
    }

    public record UsageCompletenessResponse(Instant from, Instant to, long totalCalls,
            List<UsageDimensionCompletenessResponse> dimensions) {
        static UsageCompletenessResponse from(UsageQueryService.UsageCompleteness completeness) {
            return new UsageCompletenessResponse(completeness.from(), completeness.to(), completeness.totalCalls(),
                    completeness.dimensions().stream().map(UsageDimensionCompletenessResponse::from).toList());
        }
    }

    public record UsageDimensionCompletenessResponse(String name, long present, long missing,
            java.math.BigDecimal coverage) {
        static UsageDimensionCompletenessResponse from(UsageQueryService.UsageDimensionCompleteness completeness) {
            return new UsageDimensionCompletenessResponse(completeness.name(), completeness.present(),
                    completeness.missing(), completeness.coverage());
        }
    }

    public record UsageSummaryResponse(Instant from, Instant to, long calls, long failures,
            long promptTokens, long completionTokens, double costUsd, double averageLatencyMillis,
            List<ProviderModelUsageResponse> byProviderModel,
            @JsonInclude(JsonInclude.Include.NON_NULL) String groupBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<UsageGroupResponse> groups,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer offset,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer limit,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer nextOffset) {

        static UsageSummaryResponse from(UsageQueryService.UsageSummary summary, UsageQueryService.GroupBy groupBy) {
            UsageQueryService.UsageTotals totals = summary.totals();
            boolean legacyGrouping = groupBy == UsageQueryService.GroupBy.PROVIDER_MODEL;
            return new UsageSummaryResponse(summary.from(), summary.to(), totals.calls(), totals.failures(),
                    totals.promptTokens(), totals.completionTokens(), totals.costUsd(), totals.averageLatencyMillis(),
                    legacyGrouping ? summary.groups().stream().map(ProviderModelUsageResponse::from).toList() : List.of(),
                    legacyGrouping ? null : groupBy.name().toLowerCase(java.util.Locale.ROOT),
                    legacyGrouping ? null : summary.groups().stream().map(UsageGroupResponse::from).toList(),
                    summary.offset(), summary.limit(), summary.hasMore() ? summary.offset() + summary.limit() : null);
        }
    }

    private static void appendCsvRow(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            String value = values[i] == null ? "" : values[i];
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
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
