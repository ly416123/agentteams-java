package io.agentteams.controlplane.observability;

import java.time.Duration;

/** Optional metrics port so domain services remain usable without Micrometer. */
public interface TaskMetricsPort {
    void taskCreated();
    void taskAssigned();
    void taskCompleted();
    void taskFailed();
    void taskLeaseExpired();
    void taskLeaseReleased();

    default void taskConsistencyIssue() { }
    default void taskConsistencyResolved() { }
    default void taskConsistencyScanFailed() { }

    default void outboxPublished() { }
    default void outboxRetried() { }
    default void outboxDeadLettered() { }
    default void outboxPublishFailed() { }
    default void outboxBacklog(long count) { }
    default void outboxOldestPendingAge(Duration age) { }
    default void outboxPublish(Duration duration) { }

    static TaskMetricsPort noop() {
        return new TaskMetricsPort() {
            public void taskCreated() { }
            public void taskAssigned() { }
            public void taskCompleted() { }
            public void taskFailed() { }
            public void outboxRetried() { }
            public void taskLeaseExpired() { }
            public void taskLeaseReleased() { }
        };
    }
}
