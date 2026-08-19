package io.agentteams.controlplane.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

/** Stable metric names shared by the Control Plane, Gateway and adapters. */
public final class ControlPlaneMetrics implements TaskMetricsPort {
    private final Counter tasksCreated;
    private final Counter tasksAssigned;
    private final Counter tasksCompleted;
    private final Counter tasksFailed;
    private final Counter gatewayReconnects;
    private final Counter outboxRetries;
    private final Counter taskLeasesExpired;
    private final Counter taskLeasesReleased;
    private final Timer managerLatency;

    public ControlPlaneMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        tasksCreated = registry.counter("agentteams.tasks.created");
        tasksAssigned = registry.counter("agentteams.tasks.assigned");
        tasksCompleted = registry.counter("agentteams.tasks.completed");
        tasksFailed = registry.counter("agentteams.tasks.failed");
        gatewayReconnects = registry.counter("agentteams.gateway.reconnects");
        outboxRetries = registry.counter("agentteams.outbox.retries");
        taskLeasesExpired = registry.counter("agentteams.tasks.leases.expired");
        taskLeasesReleased = registry.counter("agentteams.tasks.leases.released");
        managerLatency = registry.timer("agentteams.manager.call.latency");
    }

    public void taskCreated() { tasksCreated.increment(); }
    public void taskAssigned() { tasksAssigned.increment(); }
    public void taskCompleted() { tasksCompleted.increment(); }
    public void taskFailed() { tasksFailed.increment(); }
    public void gatewayReconnected() { gatewayReconnects.increment(); }
    public void outboxRetried() { outboxRetries.increment(); }
    public void taskLeaseExpired() { taskLeasesExpired.increment(); }
    public void taskLeaseReleased() { taskLeasesReleased.increment(); }
    public void managerCall(Duration duration) { managerLatency.record(Objects.requireNonNull(duration, "duration")); }
}
