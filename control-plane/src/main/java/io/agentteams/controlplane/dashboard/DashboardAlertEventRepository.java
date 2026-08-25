package io.agentteams.controlplane.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for idempotent dashboard alert delivery. */
public interface DashboardAlertEventRepository {
    Optional<DashboardAlertEvent> claim(DashboardAlertEvent candidate, Instant now);

    List<DashboardAlertEvent> findDue(Instant now, int limit);

    List<DashboardAlertEvent> findRecent(String tenantId, String projectId, int limit);

    List<AlertScope> findUsageScopes();

    void markSent(UUID id, Instant at);

    void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at);

    record AlertScope(String tenantId, String projectId) {
        public AlertScope {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        }
    }
}
