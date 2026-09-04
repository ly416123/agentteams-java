package io.agentteams.controlplane.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.observability.ControlPlaneMetrics;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ProjectQuotaServiceTest {
    private static final String TENANT = "tenant-a";
    private static final String PROJECT = "project-a";
    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Mock private JdbcTemplate jdbc;
    @Mock private ControlPlaneMetrics metrics;
    private ProjectQuotaService service;

    @BeforeEach
    void setUp() {
        service = new ProjectQuotaService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), metrics);
    }

    @Test
    void atomicallyReservesDailyCallTokensAndConcurrency() {
        stubRow(new RowValues(2, 3, 100, 1, 1, 20, LocalDate.of(2026, 8, 23)));

        ProjectQuotaLease lease = service.acquire(TENANT, PROJECT, 10);

        assertThat(lease.counted()).isTrue();
        verify(jdbc).update(contains("current_concurrent_calls = current_concurrent_calls + 1"),
                eq(2L), eq(30L), eq(LocalDate.of(2026, 8, 23)), any(), eq(TENANT), eq(PROJECT));
        verify(metrics).quotaAccepted();
    }

    @Test
    void rejectsWhenConcurrentLimitIsReached() {
        stubRow(new RowValues(2, 0, 0, 2, 0, 0, LocalDate.of(2026, 8, 23)));

        assertThatThrownBy(() -> service.acquire(TENANT, PROJECT, 0))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessage("project quota exceeded: concurrent_calls");

        verify(metrics).quotaRejected();
    }

    @Test
    void missingPolicyKeepsExistingUnlimitedBehavior() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<?>>any(), eq(TENANT), eq(PROJECT)))
                .thenReturn(List.of());

        ProjectQuotaLease lease = service.acquire(TENANT, PROJECT, 500);

        assertThat(lease.counted()).isFalse();
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), ArgumentMatchers.<Object[]>any());
    }

    @Test
    void releaseNeverLetsPersistentConcurrencyGoNegative() {
        service.release(new ProjectQuotaLease(TENANT, PROJECT, true));

        verify(jdbc).update(contains("GREATEST(current_concurrent_calls - 1, 0)"), any(), eq(TENANT), eq(PROJECT));
    }

    private void stubRow(RowValues values) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<?>>any(), eq(TENANT), eq(PROJECT)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet(values), 0));
                });
    }

    private static ResultSet resultSet(RowValues values) throws java.sql.SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("tenant_id")).thenReturn(TENANT);
        when(result.getString("project_id")).thenReturn(PROJECT);
        when(result.getLong("max_concurrent_calls")).thenReturn(values.maxConcurrentCalls());
        when(result.getLong("max_daily_calls")).thenReturn(values.maxDailyCalls());
        when(result.getLong("max_daily_tokens")).thenReturn(values.maxDailyTokens());
        when(result.getLong("current_concurrent_calls")).thenReturn(values.currentConcurrentCalls());
        when(result.getLong("daily_calls")).thenReturn(values.dailyCalls());
        when(result.getLong("daily_tokens")).thenReturn(values.dailyTokens());
        when(result.getObject("usage_day", LocalDate.class)).thenReturn(values.usageDay());
        return result;
    }

    private record RowValues(long maxConcurrentCalls, long maxDailyCalls, long maxDailyTokens,
            long currentConcurrentCalls, long dailyCalls, long dailyTokens, LocalDate usageDay) { }
}
