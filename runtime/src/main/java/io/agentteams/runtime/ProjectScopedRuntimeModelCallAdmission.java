package io.agentteams.runtime;

import java.util.Objects;

/**
 * Adapts project quota reservations to the runtime model-call admission port.
 * Unscoped calls retain the historical behavior and do not call the quota
 * port. A local admission can optionally be supplied so project quota and
 * worker-local concurrency are enforced together.
 */
public final class ProjectScopedRuntimeModelCallAdmission implements RuntimeModelCallAdmission {
    private final RuntimeQuotaPort quota;
    private final RuntimeModelCallAdmission localAdmission;

    public ProjectScopedRuntimeModelCallAdmission(RuntimeQuotaPort quota) {
        this(quota, RuntimeModelCallAdmission.noop());
    }

    public ProjectScopedRuntimeModelCallAdmission(RuntimeQuotaPort quota,
            RuntimeModelCallAdmission localAdmission) {
        this.quota = Objects.requireNonNull(quota, "quota");
        this.localAdmission = Objects.requireNonNull(localAdmission, "localAdmission");
    }

    @Override
    public RuntimeModelCallLease acquire(RuntimeModelCallAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        RuntimeModelCallLease localLease = localAdmission.acquire(request);
        if (localLease == null) {
            throw new IllegalStateException("local runtime model call admission returned null lease");
        }
        if (!request.hasProjectScope()) {
            return localLease;
        }
        try {
            RuntimeQuotaLease quotaLease = quota.acquire(request.tenantId(), request.projectId(), request.maxTokens());
            if (quotaLease == null) {
                throw new IllegalStateException("runtime quota port returned null lease");
            }
            return RuntimeModelCallLease.idempotent(() -> {
                try {
                    quotaLease.close();
                } finally {
                    localLease.close();
                }
            });
        } catch (RuntimeQuotaRejectedException rejected) {
            localLease.close();
            throw new RuntimeModelCallAdmissionRejectedException(
                    "project quota rejected model call" + suffix(rejected.dimension()), rejected);
        } catch (RuntimeException error) {
            localLease.close();
            throw error;
        }
    }

    private static String suffix(String dimension) {
        return dimension == null || dimension.isBlank() ? "" : ": " + dimension;
    }
}
