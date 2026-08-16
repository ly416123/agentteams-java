package io.agentteams.runtime;

@FunctionalInterface
public interface RuntimeResultSink {
    void accept(RuntimeResult result);
}
