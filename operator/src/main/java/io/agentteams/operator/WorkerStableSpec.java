package io.agentteams.operator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/** Strict decoder for the stable WorkerSpec snapshot stored with a rollout. */
public final class WorkerStableSpec {
    private WorkerStableSpec() { }

    public static WorkerSpec parse(String snapshot, String expectedAgentId) {
        return parse(snapshot, expectedAgentId, new ObjectMapper());
    }

    public static WorkerSpec parse(String snapshot, String expectedAgentId, ObjectMapper objectMapper) {
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException("previousStableSpec must not be blank");
        }
        if (expectedAgentId == null || expectedAgentId.isBlank()) {
            throw new IllegalArgumentException("expectedAgentId must not be blank");
        }
        ObjectMapper mapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        try {
            WorkerSpec spec = mapper.readValue(snapshot, WorkerSpec.class);
            if (!expectedAgentId.equals(spec.agentId())) {
                throw new IllegalArgumentException("stable WorkerSpec agentId does not match resource");
            }
            if (spec.runtime().isBlank() || spec.image().isBlank() || spec.replicas() < 1) {
                throw new IllegalArgumentException("stable WorkerSpec is incomplete");
            }
            return spec;
        } catch (IOException error) {
            throw new IllegalArgumentException("previousStableSpec is not a valid WorkerSpec", error);
        }
    }
}
