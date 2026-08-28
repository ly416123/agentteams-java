package io.agentteams.controlplane.usage;

import java.sql.Timestamp;
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
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        return summarize(from, to, null, null, ScopeFilter.explicit(tenantId, projectId));
    }

    public UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit) {
        return summarize(from, to, groupBy, limit, ScopeFilter.current());
    }

    private UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit, ScopeFilter scope) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        GroupBy grouping = GroupBy.parse(groupBy);
        int validatedLimit = validateLimit(limit);
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
                + (limit == null ? "" : " LIMIT ?");
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
                limit == null ? scope.arguments(start, end) : scope.arguments(start, end, validatedLimit));
        return new UsageSummary(range.from(), range.to(), totals, groups);
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

    private static int validateLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit == null ? 0 : limit;
    }

    public enum GroupBy {
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
                case "provider" -> PROVIDER;
                case "model" -> MODEL;
                case "status" -> STATUS;
                case "worker", "agent" -> WORKER;
                case "task" -> TASK;
                case "team" -> TEAM;
                case "tool" -> TOOL;
                case "quota" -> QUOTA;
                default -> throw new IllegalArgumentException(
                        "groupBy must be provider, model, status, worker, task, team, tool, or quota");
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

    public record UsageSummary(Instant from, Instant to, UsageTotals totals, List<UsageGroup> groups) {
        public UsageSummary {
            groups = List.copyOf(groups);
        }
    }

    public record UsageBucket(Instant bucket, long calls, long failures, long promptTokens,
            long completionTokens, double costUsd, double averageLatencyMillis) { }

    private record ScopeFilter(String clause, String tenant, String project, String team) {
        static ScopeFilter explicit(String tenant, String project) {
            return new ScopeFilter(" AND tenant_id = ? AND project_id = ?", tenant, project, null);
        }

        static ScopeFilter current() {
            return PrincipalContext.current().map(principal -> {
                String clause = " AND tenant_id = ? AND project_id = ?";
                String team = principal.scope().team();
                if (team != null && !team.isBlank()) {
                    clause += " AND (team_id IS NULL OR team_id = ?)";
                }
                return new ScopeFilter(clause, principal.scope().tenant(), principal.scope().project(), team);
            }).orElse(new ScopeFilter("", null, null, null));
        }

        String whereClause() {
            return clause;
        }

        Object[] arguments(Object... prefix) {
            Object[] result = java.util.Arrays.copyOf(prefix,
                    prefix.length + (tenant == null ? 0 : (team == null || team.isBlank() ? 2 : 3)));
            if (tenant != null) {
                result[prefix.length] = tenant;
                result[prefix.length + 1] = project;
                if (team != null && !team.isBlank()) {
                    result[prefix.length + 2] = team;
                }
            }
            return result;
        }
    }
}
