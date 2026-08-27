package io.agentteams.controlplane.worker;

import java.time.Instant;

/** Gateway-side live connection facts for one rollout. */
public record WorkerGatewayObservation(boolean online, String specDigest, String runtime,
        String configRevision, String secretGeneration, Instant observedAt) {
    public WorkerGatewayObservation {
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
    }
}
