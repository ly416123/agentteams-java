package io.agentteams.controlplane.observability;

/** Optional metrics port so domain services remain usable without Micrometer. */
public interface TaskMetricsPort {
    void taskCreated();
    void taskAssigned();
    void taskCompleted();
    void taskFailed();
    void outboxRetried();

    static TaskMetricsPort noop() {
        return new TaskMetricsPort() {
            public void taskCreated() { }
            public void taskAssigned() { }
            public void taskCompleted() { }
            public void taskFailed() { }
            public void outboxRetried() { }
        };
    }
}
