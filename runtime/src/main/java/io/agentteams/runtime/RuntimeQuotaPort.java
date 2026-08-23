package io.agentteams.runtime;

import java.util.Objects;

/**
 * Runtime quota boundary. An application may inject a local or remote-backed
 * implementation without making the runtime depend on its transport.
 */
@FunctionalInterface
public interface RuntimeQuotaPort {
    RuntimeQuotaLease acquire(String tenantId, String projectId, long estimatedTokens);

    /** Unlimited adapter for composition paths without a quota service. */
    static RuntimeQuotaPort noop() {
        return (tenantId, projectId, estimatedTokens) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(projectId, "projectId");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens must not be negative");
            }
            return RuntimeQuotaLease.noop();
        };
    }
}
