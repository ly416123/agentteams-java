package io.agentteams.controlplane.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Stable metric names shared by the Control Plane, Gateway and adapters. */
public final class ControlPlaneMetrics implements TaskMetricsPort {
    private final Counter tasksCreated;
    private final Counter tasksAssigned;
    private final Counter tasksCompleted;
    private final Counter tasksFailed;
    private final Counter gatewayReconnects;
    private final Counter outboxRetries;
    private final Counter outboxPublished;
    private final Counter outboxDeadLettered;
    private final Counter outboxPublishFailures;
    private final Counter taskLeasesExpired;
    private final Counter taskLeasesReleased;
    private final Counter mcpPolicyAllowed;
    private final Counter mcpPolicyDenied;
    private final Counter skillScansPassed;
    private final Counter skillScansFailed;
    private final Counter skillReviewsApproved;
    private final Counter skillReviewsRejected;
    private final Timer managerLatency;
    private final Timer outboxPublishLatency;
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicLong outboxOldestPendingAgeSeconds = new AtomicLong();

    public ControlPlaneMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        tasksCreated = registry.counter("agentteams.tasks.created");
        tasksAssigned = registry.counter("agentteams.tasks.assigned");
        tasksCompleted = registry.counter("agentteams.tasks.completed");
        tasksFailed = registry.counter("agentteams.tasks.failed");
        gatewayReconnects = registry.counter("agentteams.gateway.reconnects");
        outboxRetries = registry.counter("agentteams.outbox.retries");
        outboxPublished = registry.counter("agentteams.outbox.published");
        outboxDeadLettered = registry.counter("agentteams.outbox.dead_lettered");
        outboxPublishFailures = registry.counter("agentteams.outbox.publish.failures");
        taskLeasesExpired = registry.counter("agentteams.tasks.leases.expired");
        taskLeasesReleased = registry.counter("agentteams.tasks.leases.released");
        mcpPolicyAllowed = registry.counter("agentteams.mcp.policy.allowed");
        mcpPolicyDenied = registry.counter("agentteams.mcp.policy.denied");
        skillScansPassed = registry.counter("agentteams.skills.security_scans.passed");
        skillScansFailed = registry.counter("agentteams.skills.security_scans.failed");
        skillReviewsApproved = registry.counter("agentteams.skills.reviews.approved");
        skillReviewsRejected = registry.counter("agentteams.skills.reviews.rejected");
        managerLatency = registry.timer("agentteams.manager.call.latency");
        outboxPublishLatency = registry.timer("agentteams.outbox.publish.latency");
        registry.gauge("agentteams.outbox.backlog", outboxBacklog);
        registry.gauge("agentteams.outbox.oldest.pending.age.seconds", outboxOldestPendingAgeSeconds);
    }

    public void taskCreated() { tasksCreated.increment(); }
    public void taskAssigned() { tasksAssigned.increment(); }
    public void taskCompleted() { tasksCompleted.increment(); }
    public void taskFailed() { tasksFailed.increment(); }
    public void gatewayReconnected() { gatewayReconnects.increment(); }
    public void outboxRetried() { outboxRetries.increment(); }
    public void outboxPublished() { outboxPublished.increment(); }
    public void outboxDeadLettered() { outboxDeadLettered.increment(); }
    public void outboxPublishFailed() { outboxPublishFailures.increment(); }
    public void outboxBacklog(long count) { outboxBacklog.set(Math.max(0, count)); }
    public void outboxOldestPendingAge(Duration age) {
        Objects.requireNonNull(age, "age");
        outboxOldestPendingAgeSeconds.set(Math.max(0, age.getSeconds()));
    }
    public void outboxPublish(Duration duration) {
        outboxPublishLatency.record(Objects.requireNonNull(duration, "duration"));
    }
    public void taskLeaseExpired() { taskLeasesExpired.increment(); }
    public void taskLeaseReleased() { taskLeasesReleased.increment(); }
    public void mcpPolicyAllowed() { mcpPolicyAllowed.increment(); }
    public void mcpPolicyDenied() { mcpPolicyDenied.increment(); }
    public void skillScanPassed() { skillScansPassed.increment(); }
    public void skillScanFailed() { skillScansFailed.increment(); }
    public void skillReviewApproved() { skillReviewsApproved.increment(); }
    public void skillReviewRejected() { skillReviewsRejected.increment(); }
    public void managerCall(Duration duration) { managerLatency.record(Objects.requireNonNull(duration, "duration")); }
}
