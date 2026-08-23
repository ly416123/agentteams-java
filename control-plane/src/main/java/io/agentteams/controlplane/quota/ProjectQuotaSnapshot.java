package io.agentteams.controlplane.quota;

import java.time.LocalDate;

/** Queryable policy and current usage state for one project. */
public record ProjectQuotaSnapshot(String tenantId, String projectId, boolean configured,
        long maxConcurrentCalls, long maxDailyCalls, long maxDailyTokens,
        long currentConcurrentCalls, long dailyCalls, long dailyTokens, LocalDate usageDay) {
    public long remainingConcurrentCalls() {
        return remaining(maxConcurrentCalls, currentConcurrentCalls);
    }

    public long remainingDailyCalls() {
        return remaining(maxDailyCalls, dailyCalls);
    }

    public long remainingDailyTokens() {
        return remaining(maxDailyTokens, dailyTokens);
    }

    private static long remaining(long limit, long used) {
        return limit == 0 ? -1 : Math.max(0, limit - used);
    }
}
