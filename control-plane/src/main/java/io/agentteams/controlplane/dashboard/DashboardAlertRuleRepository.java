package io.agentteams.controlplane.dashboard;

import java.util.List;

/** Persistence boundary for dashboard alert rules; database adapters can implement this later. */
public interface DashboardAlertRuleRepository {
    String GLOBAL_SCOPE = "__global__";

    List<DashboardAlertRule> findAll();

    default List<DashboardAlertRule> findForScope(String tenantId, String projectId) {
        requireScope(tenantId, projectId);
        return findAll();
    }

    void save(DashboardAlertRule rule);

    default DashboardAlertRule saveForScope(String tenantId, String projectId,
            DashboardAlertRule rule, long expectedVersion) {
        requireScope(tenantId, projectId);
        if (rule == null) throw new IllegalArgumentException("rule is required");
        if (expectedVersion != 0 && expectedVersion != rule.version()) {
            throw new DashboardAlertRuleConflictException("alert rule version is stale");
        }
        DashboardAlertRule updated = new DashboardAlertRule(rule.rule(), rule.severity(), rule.threshold(),
                rule.enabled(), expectedVersion + 1);
        save(updated);
        return updated;
    }

    void delete(String rule);

    static void requireScope(String tenantId, String projectId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
    }
}
