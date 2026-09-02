package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDashboardAlertRuleRepositoryTest {
    @Test
    void readsRulesInStableOrderAndReturnsAnImmutableSnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
                new DashboardAlertRule("AVERAGE_LATENCY", "WARNING", 5000, true),
                new DashboardAlertRule("COST", "WARNING", 100, true)));
        JdbcDashboardAlertRuleRepository repository = new JdbcDashboardAlertRuleRepository(jdbc);

        List<DashboardAlertRule> rules = repository.findAll();

        assertThat(rules).containsExactly(
                new DashboardAlertRule("AVERAGE_LATENCY", "WARNING", 5000, true),
                new DashboardAlertRule("COST", "WARNING", 100, true));
        assertThatThrownBy(() -> rules.add(new DashboardAlertRule("OTHER", "INFO", 1, true)))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(jdbc).query(contains("FROM dashboard_alert_rules"), any(RowMapper.class));
    }

    @Test
    void mapsRowsThroughTheDashboardAlertRuleValueObject() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("rule")).thenReturn("cost");
        when(resultSet.getString("severity")).thenReturn("warning");
        when(resultSet.getDouble("threshold")).thenReturn(12.5);
        when(resultSet.getBoolean("enabled")).thenReturn(false);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        assertThat(new JdbcDashboardAlertRuleRepository(jdbc).findAll())
                .containsExactly(new DashboardAlertRule("COST", "WARNING", 12.5, false));
    }

    @Test
    void savesByRuleAndUpdatesExistingRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcDashboardAlertRuleRepository repository = new JdbcDashboardAlertRuleRepository(jdbc);
        DashboardAlertRule rule = new DashboardAlertRule("cost", "warning", 12.5, false);

        repository.save(rule);

        verify(jdbc).update(contains("ON CONFLICT (tenant_id, project_id, rule) DO UPDATE"),
                eq(DashboardAlertRuleRepository.GLOBAL_SCOPE), eq(DashboardAlertRuleRepository.GLOBAL_SCOPE),
                eq("COST"), eq("WARNING"), eq(12.5), eq(false), eq(0L));
    }

    @Test
    void deletesUsingTheSameCanonicalRuleKeyAsTheMemoryRepository() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcDashboardAlertRuleRepository repository = new JdbcDashboardAlertRuleRepository(jdbc);

        repository.delete(" cost ");
        repository.delete(null);

        verify(jdbc).update("DELETE FROM dashboard_alert_rules WHERE rule = ?", "COST");
    }
}
