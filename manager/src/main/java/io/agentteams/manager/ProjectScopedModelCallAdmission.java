package io.agentteams.manager;

import io.agentteams.application.api.QuotaLease;
import io.agentteams.application.api.QuotaPort;
import io.agentteams.application.api.QuotaRejectedException;
import java.util.Objects;

/**
 * Adapts project quota reservations to the manager model-call admission port.
 * Calls without a project scope retain the historical unlimited behavior.
 */
public final class ProjectScopedModelCallAdmission implements ModelCallAdmission {
    private final QuotaPort quota;

    public ProjectScopedModelCallAdmission(QuotaPort quota) {
        this.quota = Objects.requireNonNull(quota, "quota");
    }

    @Override
    public ModelCallLease acquire(ModelCallAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.hasProjectScope()) {
            return ModelCallLease.noop();
        }
        try {
            QuotaLease lease = quota.acquire(request.tenantId(), request.projectId(), request.maxTokens());
            if (lease == null) {
                throw new IllegalStateException("quota port returned null lease");
            }
            return ModelCallLease.idempotent(lease::close);
        } catch (QuotaRejectedException rejected) {
            throw new ModelCallAdmissionRejectedException(
                    "project quota rejected model call" + suffix(rejected.dimension()), rejected);
        } catch (RuntimeException unavailable) {
            throw new ModelCallAdmissionTemporaryFailureException(
                    "project quota service is unavailable", unavailable);
        }
    }

    private static String suffix(String dimension) {
        return dimension == null || dimension.isBlank() ? "" : ": " + dimension;
    }
}
