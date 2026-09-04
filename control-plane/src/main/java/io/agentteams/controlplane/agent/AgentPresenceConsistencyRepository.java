package io.agentteams.controlplane.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence boundary for the canonical agent liveness projection. */
public interface AgentPresenceConsistencyRepository {
    /** Ready agents whose gateway presence is no longer online or has stopped being refreshed. */
    List<UUID> findStaleReadyAgents(Instant lastSeenBefore, int limit);

    /** Downgrades one ready agent to offline, and reports whether its phase still said ready. */
    int markOffline(UUID agentId, Instant at);
}
