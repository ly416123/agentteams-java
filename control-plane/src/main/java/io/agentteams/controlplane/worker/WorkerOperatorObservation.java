package io.agentteams.controlplane.worker;

import java.time.Instant;

/** Operator-side deployment facts for one rollout. */
public record WorkerOperatorObservation(boolean ready, String specDigest, String runtime,
        String configRevision, String secretGeneration, Instant observedAt) {
    public WorkerOperatorObservation {
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
    }
}
