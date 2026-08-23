package io.agentteams.controlplane.dashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/** Thread-safe default store used until a durable alert-rule migration is available. */
public final class InMemoryDashboardAlertRuleRepository implements DashboardAlertRuleRepository {
    private final ConcurrentSkipListMap<String, DashboardAlertRule> rules = new ConcurrentSkipListMap<>();

    @Override
    public List<DashboardAlertRule> findAll() {
        return List.copyOf(new ArrayList<>(rules.values()));
    }

    @Override
    public void save(DashboardAlertRule rule) {
        Objects.requireNonNull(rule, "rule");
        rules.put(rule.rule(), rule);
    }

    @Override
    public void delete(String rule) {
        if (rule != null) rules.remove(rule.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
