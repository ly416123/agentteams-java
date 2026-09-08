package io.agentteams.runtime;

/**
 * Optional sink for whitelisted middle-of-execution runtime events.
 * Implementations must be cheap and failure-tolerant: runtime code invokes
 * the sink on its SSE reader thread and treats any failure as droppable.
 */
@FunctionalInterface
public interface RuntimeEventSink {
    void accept(RuntimeEvent event);
}
