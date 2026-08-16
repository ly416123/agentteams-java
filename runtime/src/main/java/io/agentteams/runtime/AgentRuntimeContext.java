package io.agentteams.runtime;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

public record AgentRuntimeContext(String runtimeName, int maxConcurrency, Clock clock,
        RuntimeResultSink resultSink, Map<String, String> configuration) {
    public AgentRuntimeContext {
        if (runtimeName == null || runtimeName.isBlank()) {
            throw new IllegalArgumentException("runtimeName must not be blank");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(resultSink, "resultSink");
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
    }
}
