package io.agentteams.controlplane.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Small deterministic repository used by unit tests and lightweight tools. */
public final class InMemoryDashboardAlertEventRepository implements DashboardAlertEventRepository {
    private final Map<String, DashboardAlertEvent> events = new LinkedHashMap<>();
    private final Map<String, AlertScope> scopes = new LinkedHashMap<>();
    private final Map<String, String> retryRequests = new LinkedHashMap<>();

    @Override
    public synchronized Optional<DashboardAlertEvent> claim(DashboardAlertEvent candidate, Instant now) {
        scopes.put(candidate.tenantId() + "\u0000" + candidate.projectId(),
                new AlertScope(candidate.tenantId(), candidate.projectId()));
        DashboardAlertEvent existing = events.get(candidate.fingerprint());
        if (existing == null) {
            events.put(candidate.fingerprint(), candidate);
            return Optional.of(candidate);
        }
        if (existing.status() == DashboardAlertEvent.Status.FAILED
                && existing.nextAttemptAt() != null && !existing.nextAttemptAt().isAfter(now)) {
            DashboardAlertEvent retry = existing.retryClaimed(now);
            events.put(existing.fingerprint(), retry);
            return Optional.of(retry);
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<RetryRequest> requestRetry(String tenantId, String projectId, UUID eventId,
            String idempotencyKey, Instant now) {
        String requestKey = eventId + "\u0000" + idempotencyKey;
        DashboardAlertEvent existing = findById(tenantId, projectId, eventId).orElse(null);
        if (existing == null) return Optional.empty();
        if (retryRequests.containsKey(requestKey)) return Optional.of(new RetryRequest(existing, true));
        if (existing.status() != DashboardAlertEvent.Status.FAILED) {
            throw new IllegalStateException("only failed alert events can be retried");
        }
        retryRequests.put(requestKey, requestKey);
        DashboardAlertEvent due = existing.retryAt(now, null, now);
        events.put(existing.fingerprint(), due);
        return Optional.of(new RetryRequest(due, false));
    }

    @Override
    public synchronized Optional<DashboardAlertEvent> findById(String tenantId, String projectId, UUID eventId) {
        return events.values().stream()
                .filter(event -> event.id().equals(eventId))
                .filter(event -> event.tenantId().equals(tenantId) && event.projectId().equals(projectId))
                .findFirst();
    }

    @Override
    public synchronized List<DashboardAlertEvent> findDue(Instant now, int limit) {
        return events.values().stream()
                .filter(event -> event.status() == DashboardAlertEvent.Status.FAILED)
                .filter(event -> event.nextAttemptAt() != null && !event.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(DashboardAlertEvent::updatedAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<DashboardAlertEvent> findRecent(String tenantId, String projectId, int limit) {
        return events.values().stream()
                .filter(event -> event.tenantId().equals(tenantId) && event.projectId().equals(projectId))
                .sorted(Comparator.comparing(DashboardAlertEvent::updatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<AlertScope> findUsageScopes() {
        return new ArrayList<>(scopes.values());
    }

    @Override
    public synchronized void markSent(UUID id, Instant at) {
        replace(id, event -> event.sentAt(at));
    }

    @Override
    public synchronized void markFailed(UUID id, Instant nextAttemptAt, String error, Instant at) {
        replace(id, event -> event.retryAt(nextAttemptAt, error, at));
    }

    private void replace(UUID id, java.util.function.UnaryOperator<DashboardAlertEvent> update) {
        String key = events.entrySet().stream().filter(entry -> entry.getValue().id().equals(id))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("alert event not found"));
        events.put(key, update.apply(events.get(key)));
    }
}
