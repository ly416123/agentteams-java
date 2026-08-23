package io.agentteams.controlplane.dashboard;

import java.util.List;

/** Persistence boundary for dashboard alert rules; database adapters can implement this later. */
public interface DashboardAlertRuleRepository {
    List<DashboardAlertRule> findAll();

    void save(DashboardAlertRule rule);

    void delete(String rule);
}
