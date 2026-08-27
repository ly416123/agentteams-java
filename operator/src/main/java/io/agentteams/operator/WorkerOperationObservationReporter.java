package io.agentteams.operator;

import java.time.Instant;

/** Reports the Kubernetes Operator's view of a Worker rollout. */
public interface WorkerOperationObservationReporter {
    void report(Worker worker, WorkerStatus status, Instant observedAt);

    static WorkerOperationObservationReporter noop() {
        return (worker, status, observedAt) -> { };
    }
}
