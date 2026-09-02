package io.agentteams.controlplane.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Thread-safe default store used until a durable alert-rule migration is available. */
public final class InMemoryDashboardAlertRuleRepository implements DashboardAlertRuleRepository {
    private final ConcurrentSkipListMap<String, DashboardAlertRule> rules = new ConcurrentSkipListMap<>();

    @Override
    public List<DashboardAlertRule> findAll() {
        return List.copyOf(rules.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(DashboardAlertRuleRepository.GLOBAL_SCOPE + ":"))
                .map(Map.Entry::getValue).toList());
    }

    @Override
    public List<DashboardAlertRule> findForScope(String tenantId, String projectId) {
        DashboardAlertRuleRepository.requireScope(tenantId, projectId);
        Map<String, DashboardAlertRule> merged = new LinkedHashMap<>();
        findAll().forEach(rule -> merged.put(rule.rule(), rule));
        rules.values().stream()
                .filter(rule -> rules.containsKey(key(tenantId, projectId, rule.rule())))
                .forEach(rule -> merged.put(rule.rule(), rule));
        return List.copyOf(merged.values());
    }

    @Override
    public void save(DashboardAlertRule rule) {
        Objects.requireNonNull(rule, "rule");
        rules.put(key(DashboardAlertRuleRepository.GLOBAL_SCOPE, DashboardAlertRuleRepository.GLOBAL_SCOPE,
                rule.rule()), rule);
    }

    @Override
    public synchronized DashboardAlertRule saveForScope(String tenantId, String projectId,
            DashboardAlertRule rule, long expectedVersion) {
        DashboardAlertRuleRepository.requireScope(tenantId, projectId);
        Objects.requireNonNull(rule, "rule");
        String key = key(tenantId, projectId, rule.rule());
        DashboardAlertRule current = rules.get(key);
        long actual = current == null ? 0 : current.version();
        if (actual != expectedVersion) throw new DashboardAlertRuleConflictException("alert rule version is stale");
        DashboardAlertRule updated = new DashboardAlertRule(rule.rule(), rule.severity(), rule.threshold(),
                rule.enabled(), actual + 1);
        rules.put(key, updated);
        return updated;
    }

    @Override
    public void delete(String rule) {
        if (rule != null) rules.remove(key(DashboardAlertRuleRepository.GLOBAL_SCOPE,
                DashboardAlertRuleRepository.GLOBAL_SCOPE, rule));
    }

    private static String key(String tenantId, String projectId, String rule) {
        return tenantId + ":" + projectId + ":" + rule.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
