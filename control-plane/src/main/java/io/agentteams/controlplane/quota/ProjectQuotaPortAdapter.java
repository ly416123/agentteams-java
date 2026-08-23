package io.agentteams.controlplane.quota;

import io.agentteams.manager.QuotaLease;
import io.agentteams.manager.QuotaPort;
import io.agentteams.manager.QuotaRejectedException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Control-plane composition adapter for the manager quota boundary.
 * The manager only sees QuotaPort; persistence and Spring types stay here.
 */
@Component
public final class ProjectQuotaPortAdapter implements QuotaPort {
    private final ProjectQuotaService quotas;

    public ProjectQuotaPortAdapter(ProjectQuotaService quotas) {
        this.quotas = Objects.requireNonNull(quotas, "quotas");
    }

    @Override
    public QuotaLease acquire(String tenantId, String projectId, long estimatedTokens) {
        try {
            ProjectQuotaLease lease = quotas.acquire(tenantId, projectId, estimatedTokens);
            if (lease == null) {
                throw new IllegalStateException("project quota service returned null lease");
            }
            return QuotaLease.idempotent(() -> quotas.release(lease));
        } catch (QuotaExceededException rejected) {
            throw new QuotaRejectedException(rejected.dimension(), rejected);
        }
    }
}
