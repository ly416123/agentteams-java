package io.agentteams.operator;

import java.util.Map;
import java.util.Objects;

public record WorkerSpec(String agentId, String runtime, String image, int replicas, Map<String, String> env) {
    public WorkerSpec {
        requireText(agentId, "agentId");
        requireText(runtime, "runtime");
        requireText(image, "image");
        if (replicas < 1) {
            throw new IllegalArgumentException("replicas must be positive");
        }
        env = Map.copyOf(Objects.requireNonNull(env, "env"));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
