package io.agentteams.controlplane.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class UsageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void aggregatesTotalsAndProviderModelGroupsForExplicitRange() {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                any(), any())).thenAnswer(invocation -> {
                    RowMapper<UsageQueryService.UsageTotals> mapper = invocation.getArgument(1);
                    return mapper.mapRow(totalsResult(), 0);
                });
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<UsageQueryService.UsageGroup> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(groupResult(), 0));
                });

        Instant from = NOW.minusSeconds(3600);
        UsageQueryService.UsageSummary summary = service().summarize(from, NOW);

        assertThat(summary.from()).isEqualTo(from);
        assertThat(summary.to()).isEqualTo(NOW);
        assertThat(summary.totals()).isEqualTo(new UsageQueryService.UsageTotals(5, 1, 100, 40, 42.5));
        assertThat(summary.groups()).containsExactly(new UsageQueryService.UsageGroup(
                "deepseek", "deepseek-chat", 5, 1, 100, 40, 42.5));
        verify(jdbc).queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                eq(Timestamp.from(from)), eq(Timestamp.from(NOW)));
    }

    @Test
    void defaultsToTheLastTwentyFourHours() {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(), any(), any()))
                .thenReturn(new UsageQueryService.UsageTotals(0, 0, 0, 0, 0));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(), any(), any()))
                .thenReturn(List.of());

        UsageQueryService.UsageSummary summary = service().summarize(null, null);

        assertThat(summary.from()).isEqualTo(NOW.minus(UsageQueryService.DEFAULT_RANGE));
        assertThat(summary.to()).isEqualTo(NOW);
    }

    @Test
    void rejectsInvalidAndOverlongRangesBeforeQuerying() {
        assertThatThrownBy(() -> service().summarize(NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must be before to");
        assertThatThrownBy(() -> service().summarize(NOW.minus(UsageQueryService.MAX_RANGE).minusSeconds(1), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("usage time range must not exceed 31 days");
    }

    @Test
    void groupsByStatusAndAppliesAnExplicitLimit() {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(), any(), any()))
                .thenReturn(new UsageQueryService.UsageTotals(8, 3, 100, 40, 42.5));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    RowMapper<UsageQueryService.UsageGroup> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(statusResult(), 0));
                });

        UsageQueryService.UsageSummary summary = service().summarize(null, NOW, "status", 10);

        assertThat(summary.groups()).containsExactly(new UsageQueryService.UsageGroup(
                null, null, 5, 1, 100, 40, 42.5, "SUCCESS"));
        verify(jdbc).query(org.mockito.ArgumentMatchers.contains("GROUP BY outcome"),
                ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(), any(), any(), eq(10));
    }

    @Test
    void scopesTotalsAndGroupsToTheAuthenticatedProject() {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                any(), any(), eq("tenant-a"), eq("project-a"), eq("team-a")))
                .thenReturn(new UsageQueryService.UsageTotals(1, 0, 10, 5, 0));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(),
                any(), any(), eq("tenant-a"), eq("project-a"), eq("team-a"))).thenReturn(List.of());
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        try {
            service().summarize(null, NOW);
            verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("tenant_id = ? AND project_id = ?"),
                    ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                    any(), any(), eq("tenant-a"), eq("project-a"), eq("team-a"));
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void summarizesAnExplicitProjectScopeWithoutUsingTheRequestPrincipal() {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                any(), any(), eq("tenant-a"), eq("project-a")))
                .thenReturn(new UsageQueryService.UsageTotals(2, 0, 10, 5, 0));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(),
                any(), any(), eq("tenant-a"), eq("project-a"))).thenReturn(List.of());

        UsageQueryService.UsageSummary summary = service().summarizeForScope("tenant-a", "project-a",
                NOW.minusSeconds(3600), NOW);

        assertThat(summary.totals().calls()).isEqualTo(2);
        verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("tenant_id = ? AND project_id = ?"),
                ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(),
                any(), any(), eq("tenant-a"), eq("project-a"));
    }

    @Test
    void reportsCompletenessForEachUsageDimensionWithinAuthenticatedScope() throws Exception {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageCompleteness>>any(),
                any(), any(), eq("tenant-a"), eq("project-a"), eq("team-a"))).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("NULLIF(BTRIM(CAST(tenant_id AS text)), '')")
                            .contains("NULLIF(BTRIM(CAST(quota_dimension AS text)), '')")
                            .doesNotContain("prompt")
                            .doesNotContain("response");
                    RowMapper<UsageQueryService.UsageCompleteness> mapper = invocation.getArgument(1);
                    return mapper.mapRow(completenessResult(), 0);
                });
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
        try {
            UsageQueryService.UsageCompleteness completeness = service().completeness(
                    NOW.minusSeconds(3600), NOW);

            assertThat(completeness.totalCalls()).isEqualTo(12);
            assertThat(completeness.dimensions()).extracting(UsageQueryService.UsageDimensionCompleteness::name)
                    .containsExactly("tenantId", "projectId", "teamId", "workerId", "taskId", "provider", "model",
                            "tool", "quotaDimension");
            UsageQueryService.UsageDimensionCompleteness worker = completeness.dimensions().stream()
                    .filter(value -> value.name().equals("workerId")).findFirst().orElseThrow();
            assertThat(worker.present()).isEqualTo(9);
            assertThat(worker.missing()).isEqualTo(3);
            assertThat(worker.coverage()).isEqualByComparingTo(new BigDecimal("0.750000"));
            verify(jdbc).queryForObject(anyString(),
                    ArgumentMatchers.<RowMapper<UsageQueryService.UsageCompleteness>>any(),
                    eq(Timestamp.from(NOW.minusSeconds(3600))), eq(Timestamp.from(NOW)),
                    eq("tenant-a"), eq("project-a"), eq("team-a"));
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void leavesCoverageEmptyWhenTheWindowHasNoCalls() throws Exception {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageCompleteness>>any(),
                any(), any())).thenAnswer(invocation -> {
                    RowMapper<UsageQueryService.UsageCompleteness> mapper = invocation.getArgument(1);
                    return mapper.mapRow(emptyCompletenessResult(), 0);
                });

        UsageQueryService.UsageCompleteness completeness = service().completeness(null, NOW);

        assertThat(completeness.totalCalls()).isZero();
        assertThat(completeness.dimensions()).allSatisfy(dimension ->
                assertThat(dimension.coverage()).isNull());
    }

    @Test
    void supportsSafeOperationalDimensionsAndKeepsMissingValuesInOneBucket() throws Exception {
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageTotals>>any(), any(), any()))
                .thenReturn(new UsageQueryService.UsageTotals(2, 0, 10, 5, 1.5));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UsageQueryService.UsageGroup>>any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("COALESCE(NULLIF(worker_id, ''), 'unknown')")
                            .doesNotContain("to_jsonb(model_call_audits)");
                    RowMapper<UsageQueryService.UsageGroup> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(dimensionResult("unknown"), 0));
                });

        UsageQueryService.UsageSummary summary = service().summarize(null, NOW, "worker", 10);

        assertThat(summary.groups()).containsExactly(new UsageQueryService.UsageGroup(
                null, null, 2, 0, 10, 5, 1.5, 12, null, "worker", "unknown"));
    }

    @Test
    void acceptsAllOperationalDimensionNames() {
        for (String dimension : List.of("worker", "task", "team", "tool", "quota")) {
            assertThat(UsageQueryService.GroupBy.parse(dimension).isDimension()).isTrue();
        }
    }

    @Test
    void rejectsUnknownGroupByAndOutOfBoundsLimitBeforeQuerying() {
        assertThatThrownBy(() -> service().summarize(null, NOW, "unknown", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("groupBy must be provider, model, status, worker, task, team, tool, or quota");
        assertThatThrownBy(() -> service().summarize(null, NOW, "provider", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and " + UsageQueryService.MAX_LIMIT);
        assertThatThrownBy(() -> service().summarize(null, NOW, "provider", UsageQueryService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and " + UsageQueryService.MAX_LIMIT);
    }

    private UsageQueryService service() {
        return new UsageQueryService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResultSet totalsResult() throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("calls")).thenReturn(5L);
        when(resultSet.getLong("failures")).thenReturn(1L);
        when(resultSet.getLong("prompt_tokens")).thenReturn(100L);
        when(resultSet.getLong("completion_tokens")).thenReturn(40L);
        when(resultSet.getDouble("cost_usd")).thenReturn(0D);
        when(resultSet.getDouble("average_latency_millis")).thenReturn(42.5D);
        return resultSet;
    }

    private static ResultSet groupResult() throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("provider")).thenReturn("deepseek");
        when(resultSet.getString("model")).thenReturn("deepseek-chat");
        when(resultSet.getLong("calls")).thenReturn(5L);
        when(resultSet.getLong("failures")).thenReturn(1L);
        when(resultSet.getLong("prompt_tokens")).thenReturn(100L);
        when(resultSet.getLong("completion_tokens")).thenReturn(40L);
        when(resultSet.getDouble("cost_usd")).thenReturn(0D);
        when(resultSet.getDouble("average_latency_millis")).thenReturn(42.5D);
        return resultSet;
    }

    private static ResultSet statusResult() throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("status")).thenReturn("SUCCESS");
        when(resultSet.getLong("calls")).thenReturn(5L);
        when(resultSet.getLong("failures")).thenReturn(1L);
        when(resultSet.getLong("prompt_tokens")).thenReturn(100L);
        when(resultSet.getLong("completion_tokens")).thenReturn(40L);
        when(resultSet.getDouble("cost_usd")).thenReturn(0D);
        when(resultSet.getDouble("average_latency_millis")).thenReturn(42.5D);
        return resultSet;
    }

    private static ResultSet dimensionResult(String value) throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("calls")).thenReturn(2L);
        when(resultSet.getLong("failures")).thenReturn(0L);
        when(resultSet.getLong("prompt_tokens")).thenReturn(10L);
        when(resultSet.getLong("completion_tokens")).thenReturn(5L);
        when(resultSet.getDouble("cost_usd")).thenReturn(1.5D);
        when(resultSet.getDouble("average_latency_millis")).thenReturn(12D);
        when(resultSet.getString("dimension_value")).thenReturn(value);
        return resultSet;
    }

    private static ResultSet completenessResult() throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("total_calls")).thenReturn(12L);
        when(resultSet.getLong("tenant_present")).thenReturn(10L);
        when(resultSet.getLong("project_present")).thenReturn(11L);
        when(resultSet.getLong("team_present")).thenReturn(8L);
        when(resultSet.getLong("worker_present")).thenReturn(9L);
        when(resultSet.getLong("task_present")).thenReturn(7L);
        when(resultSet.getLong("provider_present")).thenReturn(12L);
        when(resultSet.getLong("model_present")).thenReturn(12L);
        when(resultSet.getLong("tool_present")).thenReturn(6L);
        when(resultSet.getLong("quota_dimension_present")).thenReturn(5L);
        return resultSet;
    }

    private static ResultSet emptyCompletenessResult() throws java.sql.SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("total_calls")).thenReturn(0L);
        return resultSet;
    }
}
