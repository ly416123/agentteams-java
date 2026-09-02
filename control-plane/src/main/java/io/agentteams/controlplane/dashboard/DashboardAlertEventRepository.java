package io.agentteams.controlplane.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for idempotent dashboard alert delivery. */
public interface DashboardAlertEventRepository {
    Optional<DashboardAlertEvent> claim(DashboardAlertEvent candidate, Instant now);

    Optional<RetryRequest> requestRetry(String tenantId, String projectId, UUID eventId,
            String idempotencyKey, Instant now);

    Optional<DashboardAlertEvent> findById(String tenantId, String projectId, UUID eventId);

    List<DashboardAlertEvent> findDue(Instant now, int limit);

    List<DashboardAlertEvent> findRecent(String tenantId, String projectId, int limit);

    List<AlertScope> findUsageScopes();

    void markSent(UUID id, Instant at);

    void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at);

    record RetryRequest(DashboardAlertEvent event, boolean replayed) {
        public RetryRequest {
            if (event == null) throw new IllegalArgumentException("event is required");
        }
    }

    record AlertScope(String tenantId, String projectId) {
        public AlertScope {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        }
    }
}
