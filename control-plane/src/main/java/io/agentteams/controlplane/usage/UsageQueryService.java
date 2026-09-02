package io.agentteams.controlplane.usage;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import io.agentteams.controlplane.security.PrincipalContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only usage aggregation over the durable model call audit records. */
@Service
public final class UsageQueryService {

    static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    static final Duration MAX_RANGE = Duration.ofDays(31);
    static final int MAX_LIMIT = 1000;
    static final int MAX_OFFSET = 100_000;

    private static final String TOTALS_SQL = """
            SELECT COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE outcome = 'FAILURE') AS failures,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(cost_usd), 0) AS cost_usd,
                   COALESCE(AVG(latency_millis), 0) AS average_latency_millis,
                   COUNT(*) FILTER (WHERE cost_status = 'ESTIMATED') AS priced_calls,
                   COUNT(*) FILTER (WHERE cost_status = 'UNPRICED') AS unpriced_calls
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
            """;

    private static final String GROUPS_SQL = """
            SELECT %s,
                   COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE outcome = 'FAILURE') AS failures,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(cost_usd), 0) AS cost_usd,
                   COALESCE(AVG(latency_millis), 0) AS average_latency_millis
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
             GROUP BY %s
             ORDER BY %s
            """;

    private static final String COMPLETENESS_SQL = """
            SELECT COUNT(*) AS total_calls,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(organization_id AS text)), '') IS NOT NULL) AS organization_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(tenant_id AS text)), '') IS NOT NULL) AS tenant_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(project_id AS text)), '') IS NOT NULL) AS project_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(team_id AS text)), '') IS NOT NULL) AS team_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(actor_subject AS text)), '') IS NOT NULL) AS actor_subject_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(worker_id AS text)), '') IS NOT NULL) AS worker_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(task_id AS text)), '') IS NOT NULL) AS task_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(provider AS text)), '') IS NOT NULL) AS provider_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(model AS text)), '') IS NOT NULL) AS model_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(tool_id AS text)), '') IS NOT NULL) AS tool_present,
                   COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(quota_dimension AS text)), '') IS NOT NULL) AS quota_dimension_present
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public UsageQueryService(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")), Clock.systemUTC());
    }

    UsageQueryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UsageSummary summarize(Instant from, Instant to) {
        return summarize(from, to, null, null);
    }

    /**
     * Aggregates usage for a deployment-owned scope without consulting the
     * request thread's principal. This is the boundary used by background
     * dashboard jobs.
     */
    public UsageSummary summarizeForScope(String tenantId, String projectId, Instant from, Instant to) {
        return summarizeForScope(tenantId, projectId, from, to, null, null);
    }

    /** Aggregates a project-owned scope while retaining the dashboard grouping contract. */
    public UsageSummary summarizeForScope(String tenantId, String projectId, Instant from, Instant to,
            String groupBy, Integer limit) {
        return summarizeForScope(tenantId, projectId, from, to, groupBy, limit, UsageFilters.empty());
    }

    public UsageSummary summarizeForScope(String tenantId, String projectId, Instant from, Instant to,
            String groupBy, Integer limit, UsageFilters filters) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        return summarize(from, to, groupBy, limit,
                ScopeFilter.explicit(tenantId, projectId, filters == null ? UsageFilters.empty() : filters));
    }

    public UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit) {
        return summarize(from, to, groupBy, limit, UsageFilters.empty());
    }

    public UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit, UsageFilters filters) {
        return summarize(from, to, groupBy, limit,
                ScopeFilter.current(filters == null ? UsageFilters.empty() : filters));
    }

    /** Returns one stable page of grouped usage within the authenticated scope. */
    public UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit,
            UsageFilters filters, int offset) {
        return summarize(from, to, groupBy, limit,
                ScopeFilter.current(filters == null ? UsageFilters.empty() : filters), offset);
    }

    private UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit, ScopeFilter scope) {
        return summarize(from, to, groupBy, limit, scope, 0, false);
    }

    private UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit,
            ScopeFilter scope, int offset) {
        return summarize(from, to, groupBy, limit, scope, offset, true);
    }

    private UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit,
            ScopeFilter scope, int offset, boolean paginated) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        GroupBy grouping = GroupBy.parse(groupBy);
        int validatedLimit = validateLimit(limit);
        if (paginated && limit == null) {
            throw new IllegalArgumentException("limit is required for paginated usage");
        }
        validateOffset(offset, paginated);
        Timestamp start = Timestamp.from(range.from());
        Timestamp end = Timestamp.from(range.to());
        UsageTotals totals = jdbc.queryForObject(TOTALS_SQL + scope.whereClause(), (resultSet, rowNum) -> new UsageTotals(
                resultSet.getLong("calls"),
                resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getDouble("cost_usd"),
                resultSet.getDouble("average_latency_millis"),
                resultSet.getLong("priced_calls"), resultSet.getLong("unpriced_calls")), scope.arguments(start, end));
        if (totals == null) {
            throw new IllegalStateException("usage totals query returned no row");
        }

        String groupsSql = GROUPS_SQL.formatted(grouping.selectExpression(), grouping.groupExpression(),
                grouping.orderExpression()).replace("WHERE occurred_at >= ? AND occurred_at < ?",
                "WHERE occurred_at >= ? AND occurred_at < ?" + scope.whereClause())
                + (limit == null ? "" : " LIMIT ?" + (paginated ? " OFFSET ?" : ""));
        int queryLimit = paginated ? validatedLimit + 1 : validatedLimit;
        List<UsageGroup> groups = jdbc.query(groupsSql, (resultSet, rowNum) -> new UsageGroup(
                grouping == GroupBy.PROVIDER_MODEL || grouping == GroupBy.PROVIDER
                        ? resultSet.getString("provider") : null,
                grouping == GroupBy.PROVIDER_MODEL || grouping == GroupBy.MODEL
                        ? resultSet.getString("model") : null,
                resultSet.getLong("calls"),
                resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getDouble("cost_usd"),
                resultSet.getDouble("average_latency_millis"),
                grouping == GroupBy.STATUS ? resultSet.getString("status") : null,
                grouping.dimensionName(),
                grouping.isDimension() ? resultSet.getString("dimension_value") : null),
                limit == null ? scope.arguments(start, end)
                        : paginated ? scope.arguments(start, end, queryLimit, offset)
                                : scope.arguments(start, end, queryLimit));
        boolean hasMore = paginated && groups.size() > validatedLimit;
        List<UsageGroup> page = hasMore ? groups.subList(0, validatedLimit) : groups;
        return new UsageSummary(range.from(), range.to(), totals, page,
                paginated ? offset : null, paginated ? validatedLimit : null, hasMore);
    }

    /** Returns low-cardinality historical usage buckets for dashboard charts. */
    public List<UsageBucket> timeseries(Instant from, Instant to, String bucket) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        Bucket grouping = Bucket.parse(bucket);
        ScopeFilter scope = ScopeFilter.current();
        String expression = grouping == Bucket.HOUR ? "date_trunc('hour', occurred_at)" : "date_trunc('day', occurred_at)";
        String sql = ("""
                SELECT %s AS bucket,
                       COUNT(*) AS calls,
                       COUNT(*) FILTER (WHERE outcome = 'FAILURE') AS failures,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(cost_usd), 0) AS cost_usd,
                       COALESCE(AVG(latency_millis), 0) AS average_latency_millis
                  FROM model_call_audits
                 WHERE occurred_at >= ? AND occurred_at < ?%s
                 GROUP BY %s
                 ORDER BY %s
                """).formatted(expression, scope.whereClause(), expression, expression);
        Timestamp start = Timestamp.from(range.from());
        Timestamp end = Timestamp.from(range.to());
        return jdbc.query(sql, (resultSet, rowNum) -> new UsageBucket(
                resultSet.getTimestamp("bucket").toInstant(),
                resultSet.getLong("calls"), resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"), resultSet.getLong("completion_tokens"),
                resultSet.getDouble("cost_usd"), resultSet.getDouble("average_latency_millis")),
                scope.arguments(start, end));
    }

    /** Audits the presence of stable usage dimensions without returning their values. */
    public UsageCompleteness completeness(Instant from, Instant to) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        ScopeFilter scope = ScopeFilter.current();
        Timestamp start = Timestamp.from(range.from());
        Timestamp end = Timestamp.from(range.to());
        UsageCompleteness raw = jdbc.queryForObject(COMPLETENESS_SQL + scope.whereClause(),
                (resultSet, rowNum) -> UsageCompleteness.from(resultSet), scope.arguments(start, end));
        if (raw == null) {
            throw new IllegalStateException("usage completeness query returned no row");
        }
        return raw.withRange(range.from(), range.to());
    }

    private static int validateLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit == null ? 0 : limit;
    }

    private static void validateOffset(int offset, boolean paginated) {
        if (paginated && (offset < 0 || offset > MAX_OFFSET)) {
            throw new IllegalArgumentException("offset must be between 0 and " + MAX_OFFSET);
        }
    }

    public enum GroupBy {
        ORGANIZATION(dimensionSelect("organization_id"), dimensionExpression("organization_id"),
                dimensionExpression("organization_id"), "organization"),
        TENANT(dimensionSelect("tenant_id"), dimensionExpression("tenant_id"), dimensionExpression("tenant_id"), "tenant"),
        PROJECT(dimensionSelect("project_id"), dimensionExpression("project_id"), dimensionExpression("project_id"), "project"),
        USER(dimensionSelect("actor_subject"), dimensionExpression("actor_subject"),
                dimensionExpression("actor_subject"), "user"),
        PROVIDER_MODEL("provider, model, NULL::text AS status, NULL::text AS dimension_value",
                "provider, model", "provider, model", null),
        PROVIDER("provider, NULL::text AS model, NULL::text AS status, NULL::text AS dimension_value",
                "provider", "provider", null),
        MODEL("NULL::text AS provider, model, NULL::text AS status, NULL::text AS dimension_value",
                "model", "model", null),
        STATUS("NULL::text AS provider, NULL::text AS model, outcome AS status, NULL::text AS dimension_value",
                "outcome", "status", null),
        WORKER(dimensionSelect("worker_id"), dimensionExpression("worker_id"),
                dimensionExpression("worker_id"), "worker"),
        TASK(dimensionSelect("task_id"), dimensionExpression("task_id"),
                dimensionExpression("task_id"), "task"),
        TEAM(dimensionSelect("team_id"), dimensionExpression("team_id"),
                dimensionExpression("team_id"), "team"),
        TOOL(dimensionSelect("tool_id"), dimensionExpression("tool_id"),
                dimensionExpression("tool_id"), "tool"),
        QUOTA(dimensionSelect("quota_id", "quota_dimension"),
                dimensionExpression("quota_id", "quota_dimension"),
                dimensionExpression("quota_id", "quota_dimension"), "quota");

        private final String selectExpression;
        private final String groupExpression;
        private final String orderExpression;
        private final String dimensionName;

        GroupBy(String selectExpression, String groupExpression, String orderExpression, String dimensionName) {
            this.selectExpression = selectExpression;
            this.groupExpression = groupExpression;
            this.orderExpression = orderExpression;
            this.dimensionName = dimensionName;
        }

        String selectExpression() {
            return selectExpression;
        }

        String groupExpression() {
            return groupExpression;
        }

        String orderExpression() {
            return orderExpression;
        }

        String dimensionName() {
            return dimensionName;
        }

        boolean isDimension() {
            return dimensionName != null;
        }

        public static GroupBy parse(String value) {
            if (value == null) {
                return PROVIDER_MODEL;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "organization", "org" -> ORGANIZATION;
                case "tenant" -> TENANT;
                case "project" -> PROJECT;
                case "user", "subject", "actor" -> USER;
                case "provider" -> PROVIDER;
                case "provider_model" -> PROVIDER_MODEL;
                case "model" -> MODEL;
                case "status" -> STATUS;
                case "worker", "agent" -> WORKER;
                case "task" -> TASK;
                case "team" -> TEAM;
                case "tool" -> TOOL;
                case "quota" -> QUOTA;
                default -> throw new IllegalArgumentException(
                        "groupBy must be organization, tenant, project, user, provider_model, provider, model, status, worker, task, team, tool, or quota");
            };
        }

        private static String dimensionSelect(String... fields) {
            return "NULL::text AS provider, NULL::text AS model, NULL::text AS status, "
                    + dimensionExpression(fields) + " AS dimension_value";
        }

        private static String dimensionExpression(String... fields) {
            StringBuilder expression = new StringBuilder("COALESCE(");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) expression.append(", ");
                expression.append("NULLIF(").append(fields[i]).append(", '')");
            }
            return expression.append(", 'unknown')").toString();
        }
    }

    enum Bucket {
        HOUR, DAY;

        static Bucket parse(String value) {
            if (value == null || value.isBlank()) return HOUR;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "hour", "hourly" -> HOUR;
                case "day", "daily" -> DAY;
                default -> throw new IllegalArgumentException("bucket must be hour or day");
            };
        }
    }

    public record UsageRange(Instant from, Instant to) {
        public static UsageRange resolve(Instant requestedFrom, Instant requestedTo, Instant now) {
            Instant end = requestedTo == null ? now : requestedTo;
            Instant start = requestedFrom == null ? end.minus(DEFAULT_RANGE) : requestedFrom;
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("from must be before to");
            }
            if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
                throw new IllegalArgumentException("usage time range must not exceed 31 days");
            }
            return new UsageRange(start, end);
        }
    }

    public record UsageTotals(long calls, long failures, long promptTokens, long completionTokens,
            double costUsd, double averageLatencyMillis, long pricedCalls, long unpricedCalls) {
        public UsageTotals(long calls, long failures, long promptTokens, long completionTokens,
                double averageLatencyMillis) {
            this(calls, failures, promptTokens, completionTokens, 0, averageLatencyMillis, 0, 0);
        }

        public UsageTotals(long calls, long failures, long promptTokens, long completionTokens,
                double costUsd, double averageLatencyMillis) {
            this(calls, failures, promptTokens, completionTokens, costUsd, averageLatencyMillis, 0, 0);
        }
    }

    public record UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
            long completionTokens, double costUsd, double averageLatencyMillis, String status,
            String dimension, String dimensionValue) {
        public UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
                long completionTokens, double costUsd, double averageLatencyMillis, String status) {
            this(provider, model, calls, failures, promptTokens, completionTokens, costUsd,
                    averageLatencyMillis, status, null, null);
        }

        public UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
                long completionTokens, double averageLatencyMillis) {
            this(provider, model, calls, failures, promptTokens, completionTokens, 0, averageLatencyMillis, null);
        }

        public UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
                long completionTokens, double averageLatencyMillis, String status) {
            this(provider, model, calls, failures, promptTokens, completionTokens, 0, averageLatencyMillis, status);
        }
    }

    public record UsageSummary(Instant from, Instant to, UsageTotals totals, List<UsageGroup> groups,
            Integer offset, Integer limit, boolean hasMore) {
        public UsageSummary(Instant from, Instant to, UsageTotals totals, List<UsageGroup> groups) {
            this(from, to, totals, groups, null, null, false);
        }

        public UsageSummary {
            groups = List.copyOf(groups);
            if (offset == null && limit != null) {
                throw new IllegalArgumentException("limit requires offset");
            }
            if (offset != null && (limit == null || offset < 0 || limit < 1)) {
                throw new IllegalArgumentException("invalid usage pagination metadata");
            }
        }
    }

    /** Optional low-cardinality filters applied inside the authenticated project scope. */
    public record UsageFilters(String taskId, String provider, String model) {
        public UsageFilters {
            taskId = normalize(taskId);
            provider = normalize(provider);
            model = normalize(model);
        }

        public static UsageFilters empty() {
            return new UsageFilters(null, null, null);
        }

        boolean hasAny() {
            return taskId != null || provider != null || model != null;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public record UsageBucket(Instant bucket, long calls, long failures, long promptTokens,
            long completionTokens, double costUsd, double averageLatencyMillis) { }

    public record UsageDimensionCompleteness(String name, long present, long missing, BigDecimal coverage) {
        static UsageDimensionCompleteness of(String name, long present, long totalCalls) {
            long missing = totalCalls - present;
            BigDecimal coverage = totalCalls == 0 ? null
                    : BigDecimal.valueOf(present)
                            .divide(BigDecimal.valueOf(totalCalls), 6, RoundingMode.HALF_UP);
            return new UsageDimensionCompleteness(name, present, missing, coverage);
        }
    }

    public record UsageCompleteness(Instant from, Instant to, long totalCalls,
            List<UsageDimensionCompleteness> dimensions) {
        public UsageCompleteness {
            dimensions = List.copyOf(dimensions);
        }

        private static UsageCompleteness from(java.sql.ResultSet resultSet) throws java.sql.SQLException {
            long totalCalls = resultSet.getLong("total_calls");
            return new UsageCompleteness(null, null, totalCalls, List.of(
                    UsageDimensionCompleteness.of("organizationId", resultSet.getLong("organization_present"), totalCalls),
                    UsageDimensionCompleteness.of("tenantId", resultSet.getLong("tenant_present"), totalCalls),
                    UsageDimensionCompleteness.of("projectId", resultSet.getLong("project_present"), totalCalls),
                    UsageDimensionCompleteness.of("teamId", resultSet.getLong("team_present"), totalCalls),
                    UsageDimensionCompleteness.of("actorSubject", resultSet.getLong("actor_subject_present"), totalCalls),
                    UsageDimensionCompleteness.of("workerId", resultSet.getLong("worker_present"), totalCalls),
                    UsageDimensionCompleteness.of("taskId", resultSet.getLong("task_present"), totalCalls),
                    UsageDimensionCompleteness.of("provider", resultSet.getLong("provider_present"), totalCalls),
                    UsageDimensionCompleteness.of("model", resultSet.getLong("model_present"), totalCalls),
                    UsageDimensionCompleteness.of("tool", resultSet.getLong("tool_present"), totalCalls),
                    UsageDimensionCompleteness.of("quotaDimension",
                            resultSet.getLong("quota_dimension_present"), totalCalls)));
        }

        private UsageCompleteness withRange(Instant rangeFrom, Instant rangeTo) {
            return new UsageCompleteness(rangeFrom, rangeTo, totalCalls, dimensions);
        }
    }

    private record ScopeFilter(String clause, List<Object> values) {
        static ScopeFilter explicit(String tenant, String project, UsageFilters filters) {
            StringBuilder clause = new StringBuilder(" AND tenant_id = ? AND project_id = ?");
            List<Object> values = new java.util.ArrayList<>(List.of(tenant, project));
            appendFilters(clause, values, filters);
            return new ScopeFilter(clause.toString(), List.copyOf(values));
        }

        static ScopeFilter current() {
            return current(UsageFilters.empty());
        }

        static ScopeFilter current(UsageFilters filters) {
            return PrincipalContext.current().map(principal -> {
                StringBuilder clause = new StringBuilder(" AND tenant_id = ? AND project_id = ?");
                List<Object> values = new java.util.ArrayList<>(List.of(
                        principal.scope().tenant(), principal.scope().project()));
                String team = principal.scope().team();
                if (team != null && !team.isBlank()) {
                    clause.append(" AND (team_id IS NULL OR team_id = ?)");
                    values.add(team);
                }
                appendFilters(clause, values, filters);
                return new ScopeFilter(clause.toString(), List.copyOf(values));
            }).orElse(new ScopeFilter("", List.of()));
        }

        private static void appendFilters(StringBuilder clause, List<Object> values, UsageFilters filters) {
            if (filters.taskId() != null) {
                clause.append(" AND task_id = ?");
                values.add(filters.taskId());
            }
            if (filters.provider() != null) {
                clause.append(" AND provider = ?");
                values.add(filters.provider());
            }
            if (filters.model() != null) {
                clause.append(" AND model = ?");
                values.add(filters.model());
            }
        }

        String whereClause() {
            return clause;
        }

        Object[] arguments(Object... prefix) {
            Object[] result = java.util.Arrays.copyOf(prefix, prefix.length + values.size());
            for (int i = 0; i < values.size(); i++) {
                result[prefix.length + i] = values.get(i);
            }
            return result;
        }
    }
}
