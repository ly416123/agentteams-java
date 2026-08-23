package io.agentteams.controlplane.usage;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
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
                   COALESCE(AVG(latency_millis), 0) AS average_latency_millis
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
            """;

    private static final String GROUPS_SQL = """
            SELECT %s,
                   COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE outcome = 'FAILURE') AS failures,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(AVG(latency_millis), 0) AS average_latency_millis
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
             GROUP BY %s
             ORDER BY %s
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

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

    public UsageSummary summarize(Instant from, Instant to, String groupBy, Integer limit) {
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
        GroupBy grouping = GroupBy.parse(groupBy);
        int validatedLimit = validateLimit(limit);
        Timestamp start = Timestamp.from(range.from());
        Timestamp end = Timestamp.from(range.to());

        UsageTotals totals = jdbc.queryForObject(TOTALS_SQL, (resultSet, rowNum) -> new UsageTotals(
                resultSet.getLong("calls"),
                resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getDouble("average_latency_millis")), start, end);
        if (totals == null) {
            throw new IllegalStateException("usage totals query returned no row");
        }

        String groupsSql = GROUPS_SQL.formatted(grouping.selectExpression(), grouping.groupExpression(),
                grouping.orderExpression()) + (limit == null ? "" : " LIMIT ?");
        List<UsageGroup> groups = jdbc.query(groupsSql, (resultSet, rowNum) -> new UsageGroup(
                grouping == GroupBy.PROVIDER_MODEL || grouping == GroupBy.PROVIDER
                        ? resultSet.getString("provider") : null,
                grouping == GroupBy.PROVIDER_MODEL || grouping == GroupBy.MODEL
                        ? resultSet.getString("model") : null,
                resultSet.getLong("calls"),
                resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getDouble("average_latency_millis"),
                grouping == GroupBy.STATUS ? resultSet.getString("status") : null),
                limit == null ? new Object[] {start, end} : new Object[] {start, end, validatedLimit});
        return new UsageSummary(range.from(), range.to(), totals, groups);
    }

    private static int validateLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit == null ? 0 : limit;
    }

    enum GroupBy {
        PROVIDER_MODEL(null, "provider, model", "provider, model"),
        PROVIDER("provider, NULL::text AS model, NULL::text AS status", "provider", "provider"),
        MODEL("NULL::text AS provider, model, NULL::text AS status", "model", "model"),
        STATUS("NULL::text AS provider, NULL::text AS model, outcome AS status", "outcome", "status");

        private final String selectExpression;
        private final String groupExpression;
        private final String orderExpression;

        GroupBy(String selectExpression, String groupExpression, String orderExpression) {
            this.selectExpression = selectExpression;
            this.groupExpression = groupExpression;
            this.orderExpression = orderExpression;
        }

        String selectExpression() {
            return selectExpression == null ? "provider, model, NULL::text AS status" : selectExpression;
        }

        String groupExpression() {
            return groupExpression;
        }

        String orderExpression() {
            return orderExpression;
        }

        static GroupBy parse(String value) {
            if (value == null) {
                return PROVIDER_MODEL;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "provider" -> PROVIDER;
                case "model" -> MODEL;
                case "status" -> STATUS;
                default -> throw new IllegalArgumentException("groupBy must be provider, model, or status");
            };
        }
    }

    record UsageRange(Instant from, Instant to) {
        static UsageRange resolve(Instant requestedFrom, Instant requestedTo, Instant now) {
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
            double averageLatencyMillis) {
    }

    public record UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
            long completionTokens, double averageLatencyMillis, String status) {
        public UsageGroup(String provider, String model, long calls, long failures, long promptTokens,
                long completionTokens, double averageLatencyMillis) {
            this(provider, model, calls, failures, promptTokens, completionTokens, averageLatencyMillis, null);
        }
    }

    public record UsageSummary(Instant from, Instant to, UsageTotals totals, List<UsageGroup> groups) {
        public UsageSummary {
            groups = List.copyOf(groups);
        }
    }
}
