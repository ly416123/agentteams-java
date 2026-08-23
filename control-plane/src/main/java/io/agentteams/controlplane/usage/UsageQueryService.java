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
            SELECT provider,
                   model,
                   COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE outcome = 'FAILURE') AS failures,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(AVG(latency_millis), 0) AS average_latency_millis
              FROM model_call_audits
             WHERE occurred_at >= ? AND occurred_at < ?
             GROUP BY provider, model
             ORDER BY provider, model
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
        UsageRange range = UsageRange.resolve(from, to, clock.instant());
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

        List<UsageGroup> groups = jdbc.query(GROUPS_SQL, (resultSet, rowNum) -> new UsageGroup(
                resultSet.getString("provider"),
                resultSet.getString("model"),
                resultSet.getLong("calls"),
                resultSet.getLong("failures"),
                resultSet.getLong("prompt_tokens"),
                resultSet.getLong("completion_tokens"),
                resultSet.getDouble("average_latency_millis")), start, end);
        return new UsageSummary(range.from(), range.to(), totals, groups);
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
            long completionTokens, double averageLatencyMillis) {
    }

    public record UsageSummary(Instant from, Instant to, UsageTotals totals, List<UsageGroup> groups) {
        public UsageSummary {
            groups = List.copyOf(groups);
        }
    }
}
