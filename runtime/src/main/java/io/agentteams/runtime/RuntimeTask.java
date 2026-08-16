package io.agentteams.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RuntimeTask(UUID id, String taskType, String inputJson, Map<String, String> metadata) {
    public RuntimeTask {
        Objects.requireNonNull(id, "id");
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        if (inputJson == null || inputJson.isBlank()) {
            throw new IllegalArgumentException("inputJson must not be blank");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
