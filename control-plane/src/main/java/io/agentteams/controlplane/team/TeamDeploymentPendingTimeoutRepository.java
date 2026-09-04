package io.agentteams.controlplane.team;

import java.time.Instant;

/** Fails team deployment members whose config apply record stopped being refreshed before a cutoff. */
public interface TeamDeploymentPendingTimeoutRepository {
    /**
     * Marks still-pending members as FAILED/APPLY_TIMEOUT when their config apply record is older than
     * the cutoff, then refreshes the aggregate status of every touched deployment.
     *
     * @param now the reconciliation instant, used for the aggregate refresh bookkeeping
     * @param applyUpdatedBefore the cutoff for {@code config_apply_records.updated_at}
     * @param limit maximum number of members to fail in one batch
     * @return the number of members failed
     */
    int failStalePendingMembers(Instant now, Instant applyUpdatedBefore, int limit);

    /**
     * Re-aggregates deployments that are stuck at PENDING even though none of their members is
     * pending any more (data written by older releases that skipped the aggregate refresh on
     * ACK). Idempotent: re-running on already-converged data repairs nothing.
     *
     * @param limit maximum number of deployments to repair in one batch
     * @return the number of deployments whose aggregate status was refreshed
     */
    int refreshPendingAggregates(int limit);
}
