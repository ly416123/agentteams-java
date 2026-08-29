package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.worker.WorkerOperation;
import io.agentteams.controlplane.worker.WorkerOperationService;
import io.agentteams.controlplane.worker.WorkerOperationStatus;
import io.agentteams.controlplane.worker.WorkerOperationType;
import io.agentteams.controlplane.worker.WorkerOperationObservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerOperationListControllerTest {
    @Test
    void returnsRedactedStableOperationHistoryForAnAgent() throws Exception {
        WorkerOperationService operations = mock(WorkerOperationService.class);
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        WorkerOperation operation = new WorkerOperation(UUID.randomUUID(), agentId, WorkerOperationType.ROLLOUT,
                WorkerOperationStatus.FAILED, "sha256:image", "qwenpaw", "config-7", "secret-generation-3",
                "{\"token\":\"private-token\"}", "idempotency-key", 4, "actor-a", null,
                "AUTH_FAILED", "correlation-1", now, now, 2);
        WorkerOperationObservation observation = new WorkerOperationObservation(operation.id(), true, "sha256:image",
                "qwenpaw", "config-7", "private-secret-generation", now, true, "sha256:image", "qwenpaw",
                "config-7", "private-gateway-secret-generation", now, now);
        when(operations.list(any(), any())).thenReturn(new CursorPage<>(List.of(operation), null, false, now));
        when(operations.observation(operation.id())).thenReturn(java.util.Optional.of(observation));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(mock(io.agentteams.controlplane.service.AgentService.class),
                operations)).setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(get("/api/v1/agents/{agentId}/operations", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("ROLLOUT"))
                .andExpect(jsonPath("$.items[0].failureCategory").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.items[0].operatorReady").value(true))
                .andExpect(jsonPath("$.items[0].gatewayOnline").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-generation-3"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-token"))));
        org.assertj.core.api.Assertions.assertThat(
                mvc.perform(get("/api/v1/agents/{agentId}/operations", agentId)).andReturn()
                        .getResponse().getContentAsString())
                .doesNotContain("requestedSecretGeneration", "previousStableSpec", "operatorSecretGeneration",
                        "gatewaySecretGeneration");
    }
}
