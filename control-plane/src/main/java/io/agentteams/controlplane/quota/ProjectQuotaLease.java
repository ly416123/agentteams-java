package io.agentteams.controlplane.quota;

import java.util.Objects;

/** A concurrency reservation that must be released when the call finishes. */
public record ProjectQuotaLease(String tenantId, String projectId, boolean counted) {
    public ProjectQuotaLease {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
    }
}
