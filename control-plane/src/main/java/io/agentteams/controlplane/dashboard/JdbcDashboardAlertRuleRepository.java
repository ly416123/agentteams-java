package io.agentteams.controlplane.dashboard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable JDBC store for dashboard alert rules. */
@Repository
public final class JdbcDashboardAlertRuleRepository implements DashboardAlertRuleRepository {
    private static final String SELECT_RULES = """
            SELECT rule, severity, threshold, enabled
              FROM dashboard_alert_rules
             ORDER BY rule
            """;

    private final JdbcTemplate jdbc;

    public JdbcDashboardAlertRuleRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcDashboardAlertRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<DashboardAlertRule> findAll() {
        return List.copyOf(jdbc.query(SELECT_RULES, (rs, row) -> new DashboardAlertRule(
                rs.getString("rule"), rs.getString("severity"), rs.getDouble("threshold"),
                rs.getBoolean("enabled"))));
    }

    @Override
    public void save(DashboardAlertRule rule) {
        Objects.requireNonNull(rule, "rule");
        jdbc.update("""
                INSERT INTO dashboard_alert_rules (rule, severity, threshold, enabled)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (rule) DO UPDATE SET severity = EXCLUDED.severity,
                    threshold = EXCLUDED.threshold, enabled = EXCLUDED.enabled
                """, rule.rule(), rule.severity(), rule.threshold(), rule.enabled());
    }

    @Override
    public void delete(String rule) {
        if (rule == null) return;
        jdbc.update("DELETE FROM dashboard_alert_rules WHERE rule = ?", normalizeRule(rule));
    }

    private static String normalizeRule(String rule) {
        return rule.trim().toUpperCase(Locale.ROOT);
    }
}
