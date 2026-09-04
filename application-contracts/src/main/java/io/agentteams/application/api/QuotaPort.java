package io.agentteams.application.api;

import java.util.Objects;

/**
 * Manager-side quota boundary. Implementations may be local, remote, or
 * supplied by the control plane; no control-plane type crosses this boundary.
 */
@FunctionalInterface
public interface QuotaPort {
    QuotaLease acquire(String tenantId, String projectId, long estimatedTokens);

    /** Unlimited adapter used by legacy composition paths. */
    static QuotaPort noop() {
        return (tenantId, projectId, estimatedTokens) -> {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(projectId, "projectId");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens must not be negative");
            }
            return QuotaLease.noop();
        };
    }
}
