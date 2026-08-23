package io.agentteams.controlplane.quota;

/** Project-level limits; zero means unlimited for that dimension. */
public record ProjectQuotaPolicy(String tenantId, String projectId,
        long maxConcurrentCalls, long maxDailyCalls, long maxDailyTokens) {
    public ProjectQuotaPolicy {
        requireScopePart(tenantId, "tenantId");
        requireScopePart(projectId, "projectId");
        requireNonNegative(maxConcurrentCalls, "maxConcurrentCalls");
        requireNonNegative(maxDailyCalls, "maxDailyCalls");
        requireNonNegative(maxDailyTokens, "maxDailyTokens");
    }

    private static void requireScopePart(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
