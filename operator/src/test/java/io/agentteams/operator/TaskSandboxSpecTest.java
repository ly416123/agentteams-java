package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TaskSandboxSpecTest {

    @Test
    void deserializesAllFieldsDeclaredByTaskSandboxCrd() throws Exception {
        String json = """
                {
                  "taskId": "task-1",
                  "attemptId": "attempt-1",
                  "idempotencyKey": "sandbox:task-1",
                  "profile": "ISOLATED",
                  "runtimeClassName": "gvisor",
                  "image": "ghcr.io/ly416123/agentteams-task-sandbox:latest",
                  "ttlSeconds": 300,
                  "template": "python-untrusted",
                  "expiresAt": "2026-08-30T00:00:00Z",
                  "terminationRequested": false,
                  "terminationReason": "",
                  "resources": {"cpu": "250m", "memory": "256Mi"}
                }
                """;

        TaskSandboxSpec spec = new ObjectMapper().readValue(json, TaskSandboxSpec.class);

        assertThat(spec.getTaskId()).isEqualTo("task-1");
        assertThat(spec.getAttemptId()).isEqualTo("attempt-1");
        assertThat(spec.getIdempotencyKey()).isEqualTo("sandbox:task-1");
        assertThat(spec.getProfile()).isEqualTo(io.agentteams.application.api.SandboxProfile.ISOLATED);
        assertThat(spec.getTemplate()).isEqualTo("python-untrusted");
        assertThat(spec.getExpiresAt()).isEqualTo("2026-08-30T00:00:00Z");
        assertThat(spec.getTerminationReason()).isEmpty();
        assertThat(spec.getResources()).containsEntry("cpu", "250m");
    }
}
