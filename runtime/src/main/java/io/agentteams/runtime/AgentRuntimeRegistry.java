package io.agentteams.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime selection boundary for Agent implementations. */
public final class AgentRuntimeRegistry {
    private final Map<String, AgentRuntime> runtimes = new ConcurrentHashMap<>();
    private final String defaultRuntime;

    public AgentRuntimeRegistry(String defaultRuntime, Map<String, ? extends AgentRuntime> runtimes) {
        this.defaultRuntime = requireText(defaultRuntime, "defaultRuntime");
        Objects.requireNonNull(runtimes, "runtimes").forEach(this::register);
        if (!this.runtimes.containsKey(this.defaultRuntime)) {
            throw new IllegalArgumentException("default runtime is not registered: " + this.defaultRuntime);
        }
    }

    public void register(String name, AgentRuntime runtime) {
        runtimes.put(requireText(name, "name"), Objects.requireNonNull(runtime, "runtime"));
    }

    public AgentRuntime defaultRuntime() {
        return resolve(defaultRuntime);
    }

    public AgentRuntime resolve(String name) {
        String key = requireText(name, "name");
        AgentRuntime runtime = runtimes.get(key);
        if (runtime == null) {
            throw new IllegalArgumentException("Agent runtime is not registered: " + key);
        }
        return runtime;
    }

    public Map<String, AgentRuntime> runtimes() {
        return Map.copyOf(runtimes);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
