package io.agentteams.controlplane.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repairs the canonical agent phase from the gateway presence projection. */
public final class AgentPresenceConsistencyService {
    private static final int MAX_BATCH_SIZE = 1000;
    private static final Logger log = LoggerFactory.getLogger(AgentPresenceConsistencyService.class);

    private final AgentPresenceConsistencyRepository repository;

    public AgentPresenceConsistencyService(AgentPresenceConsistencyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ReconcileResult reconcile(Instant now, Duration staleAfter, int batchSize) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        List<UUID> stale = repository.findStaleReadyAgents(now.minus(staleAfter), batchSize);
        int repaired = 0;
        int failures = 0;
        for (UUID agentId : stale) {
            try {
                repaired += repository.markOffline(agentId, now);
            } catch (RuntimeException failure) {
                failures++;
                log.warn("Agent presence reconciliation failed agentId={} errorType={}",
                        agentId, failure.getClass().getSimpleName());
            }
        }
        return new ReconcileResult(stale.size(), repaired, failures);
    }

    public record ReconcileResult(int scanned, int repaired, int failures) {
        public ReconcileResult {
            if (scanned < 0 || repaired < 0 || failures < 0) {
                throw new IllegalArgumentException("reconcile counts must not be negative");
            }
        }
    }
}
