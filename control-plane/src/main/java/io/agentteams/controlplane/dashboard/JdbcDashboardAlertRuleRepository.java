package io.agentteams.controlplane.dashboard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable JDBC store for dashboard alert rules. */
@Repository
public class JdbcDashboardAlertRuleRepository implements DashboardAlertRuleRepository {
    private static final String SELECT_RULES = """
            SELECT rule, severity, threshold, enabled, version
              FROM dashboard_alert_rules
             WHERE tenant_id = '__global__' AND project_id = '__global__'
             ORDER BY rule
            """;
    private static final String SELECT_SCOPED_RULES = """
            SELECT rule, severity, threshold, enabled, version
              FROM dashboard_alert_rules
             WHERE tenant_id = ? AND project_id = ?
             ORDER BY rule
            """;

    private final JdbcTemplate jdbc;

    @Autowired
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
                rs.getBoolean("enabled"), rs.getLong("version"))));
    }

    @Override
    public List<DashboardAlertRule> findForScope(String tenantId, String projectId) {
        DashboardAlertRuleRepository.requireScope(tenantId, projectId);
        List<DashboardAlertRule> merged = new java.util.ArrayList<>(findAll());
        List<DashboardAlertRule> scoped = jdbc.query(SELECT_SCOPED_RULES,
                (rs, row) -> new DashboardAlertRule(rs.getString("rule"), rs.getString("severity"),
                        rs.getDouble("threshold"), rs.getBoolean("enabled"), rs.getLong("version")),
                tenantId, projectId);
        scoped.forEach(rule -> {
            merged.removeIf(existing -> existing.rule().equals(rule.rule()));
            merged.add(rule);
        });
        merged.sort(java.util.Comparator.comparing(DashboardAlertRule::rule));
        return List.copyOf(merged);
    }

    @Override
    public void save(DashboardAlertRule rule) {
        Objects.requireNonNull(rule, "rule");
        jdbc.update("""
                INSERT INTO dashboard_alert_rules (tenant_id, project_id, rule, severity, threshold, enabled, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, rule) DO UPDATE SET severity = EXCLUDED.severity,
                    threshold = EXCLUDED.threshold, enabled = EXCLUDED.enabled, version = EXCLUDED.version
                """, DashboardAlertRuleRepository.GLOBAL_SCOPE, DashboardAlertRuleRepository.GLOBAL_SCOPE,
                rule.rule(), rule.severity(), rule.threshold(), rule.enabled(), rule.version());
    }

    @Override
    public synchronized DashboardAlertRule saveForScope(String tenantId, String projectId,
            DashboardAlertRule rule, long expectedVersion) {
        DashboardAlertRuleRepository.requireScope(tenantId, projectId);
        Objects.requireNonNull(rule, "rule");
        int updated = jdbc.update("""
                UPDATE dashboard_alert_rules
                   SET severity = ?, threshold = ?, enabled = ?, version = version + 1
                 WHERE tenant_id = ? AND project_id = ? AND rule = ? AND version = ?
                """, rule.severity(), rule.threshold(), rule.enabled(), tenantId, projectId, rule.rule(),
                expectedVersion);
        if (updated == 1) {
            return new DashboardAlertRule(rule.rule(), rule.severity(), rule.threshold(), rule.enabled(),
                    expectedVersion + 1);
        }
        if (expectedVersion == 0) {
            int inserted = jdbc.update("""
                    INSERT INTO dashboard_alert_rules (tenant_id, project_id, rule, severity, threshold, enabled, version)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (tenant_id, project_id, rule) DO NOTHING
                    """, tenantId, projectId, rule.rule(), rule.severity(), rule.threshold(), rule.enabled());
            if (inserted == 1) {
                return new DashboardAlertRule(rule.rule(), rule.severity(), rule.threshold(), rule.enabled(), 1);
            }
        }
        throw new DashboardAlertRuleConflictException("alert rule version is stale");
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
