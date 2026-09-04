package io.agentteams.controlplane.team;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converges team deployment members that will never be acknowledged into a terminal FAILED state. */
public final class TeamDeploymentPendingTimeoutService {
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Logger log = LoggerFactory.getLogger(TeamDeploymentPendingTimeoutService.class);

    private final TeamDeploymentPendingTimeoutRepository repository;

    public TeamDeploymentPendingTimeoutService(TeamDeploymentPendingTimeoutRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public TimeoutResult reconcile(Instant now, Duration pendingTimeout, int batchSize) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(pendingTimeout, "pendingTimeout");
        if (pendingTimeout.isNegative() || pendingTimeout.isZero()) {
            throw new IllegalArgumentException("pendingTimeout must be positive");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        try {
            int failed = repository.failStalePendingMembers(now, now.minus(pendingTimeout), batchSize);
            int repaired = repository.refreshPendingAggregates(batchSize);
            return new TimeoutResult(failed, repaired);
        } catch (RuntimeException failure) {
            // A failed batch is retried by the next scheduled run; the scan itself is one statement.
            log.warn("Team deployment pending timeout failed errorType={}", failure.getClass().getSimpleName());
            return new TimeoutResult(0, 0);
        }
    }

    public record TimeoutResult(int failed, int repaired) {
        public TimeoutResult {
            if (failed < 0 || repaired < 0) {
                throw new IllegalArgumentException("failed and repaired must not be negative");
            }
        }
    }
}
