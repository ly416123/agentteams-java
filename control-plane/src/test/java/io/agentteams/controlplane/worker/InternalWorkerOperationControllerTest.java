package io.agentteams.controlplane.worker;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalWorkerOperationControllerTest {
    @Mock private WorkerOperationService operations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalWorkerOperationController(operations, "secret"))
                .build();
    }

    @Test
    void rejectsRequestsWithoutInternalToken() throws Exception {
        mockMvc.perform(post("/internal/v1/worker-operations/{id}/confirm", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmsRolloutUsingTheAuthenticatedInternalBoundary() throws Exception {
        UUID operationId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(operationId, UUID.randomUUID(),
                WorkerOperationType.ROLLOUT, "sha256:new", "qwenpaw", "config-2", "secret-2", "{}",
                "rollout-1", 2, "operator", Instant.parse("2030-01-01T00:02:00Z"), "correlation-1",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(operations.confirmRollout(eq(operationId), eq(0L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(operation);

        mockMvc.perform(post("/internal/v1/worker-operations/{id}/confirm", operationId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"operatorReady":true,
                                 "operatorSpecDigest":"sha256:new","operatorRuntime":"qwenpaw",
                                 "operatorConfigRevision":"config-2","operatorSecretGeneration":"secret-2",
                                 "gatewayOnline":true,"gatewaySpecDigest":"sha256:new",
                                 "gatewayRuntime":"qwenpaw","gatewayConfigRevision":"config-2",
                                 "gatewaySecretGeneration":"secret-2",
                                 "observedAt":"2030-01-01T00:01:00Z"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(operationId.toString()));

        ArgumentCaptor<WorkerRolloutConfirmation> confirmation = ArgumentCaptor.forClass(WorkerRolloutConfirmation.class);
        verify(operations).confirmRollout(eq(operationId), eq(0L), confirmation.capture());
        org.assertj.core.api.Assertions.assertThat(confirmation.getValue().operatorSpecDigest())
                .isEqualTo("sha256:new");
    }

    @Test
    void acceptsOperatorAndGatewayReportsThroughSeparateEndpoints() throws Exception {
        UUID operationId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(operationId, UUID.randomUUID(),
                WorkerOperationType.ROLLOUT, "sha256:image", "qwenpaw", "config-2", "secret-2", "{}",
                "rollout-1", 1, "operator", Instant.parse("2030-01-01T00:02:00Z"), "correlation-1",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(operations.confirmOperator(eq(operationId), eq(0L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(operation);
        when(operations.confirmGateway(eq(operationId), eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(operation);

        mockMvc.perform(post("/internal/v1/worker-operations/{id}/operator", operationId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"ready":true,"specDigest":"sha256:image",
                                 "runtime":"qwenpaw","configRevision":"config-2",
                                 "secretGeneration":"secret-2","observedAt":"2030-01-01T00:01:00Z"}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/internal/v1/worker-operations/{id}/gateway", operationId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":1,"online":true,"specDigest":"sha256:image",
                                 "runtime":"qwenpaw","configRevision":"config-2",
                                 "secretGeneration":"secret-2","observedAt":"2030-01-01T00:01:01Z"}
                                """))
                .andExpect(status().isAccepted());

        verify(operations).confirmOperator(eq(operationId), eq(0L),
                org.mockito.ArgumentMatchers.any(WorkerOperatorObservation.class));
        verify(operations).confirmGateway(eq(operationId), eq(1L),
                org.mockito.ArgumentMatchers.any(WorkerGatewayObservation.class));
    }

    @Test
    void returnsLiveOperationToTokenAuthenticatedObservationAdapters() throws Exception {
        UUID agentId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(UUID.randomUUID(), agentId, WorkerOperationType.ROLLOUT,
                "sha256:image", "qwenpaw", "config-2", "secret-2", "{}", "lookup-1", 3,
                "operator", Instant.parse("2030-01-01T00:02:00Z"), "correlation-1",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(operations.active(eq(agentId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(operation));

        mockMvc.perform(get("/internal/v1/worker-operations/active/{agentId}", agentId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operation.id().toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.requestedRuntime").value("qwenpaw"))
                .andExpect(jsonPath("$.requestedSecretGeneration").value("secret-2"));

        verify(operations).active(eq(agentId), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotExposeMissingLiveOperationAsAFalseSuccess() throws Exception {
        UUID agentId = UUID.randomUUID();
        when(operations.active(eq(agentId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/internal/v1/worker-operations/active/{agentId}", agentId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret"))
                .andExpect(status().is(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    void exposesFailedRolloutForAutomaticStableSpecRecovery() throws Exception {
        UUID agentId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(UUID.randomUUID(), agentId, WorkerOperationType.ROLLOUT,
                "sha256:new", "qwenpaw", "config-2", "secret-2",
                "{\"agentId\":\"" + agentId + "\",\"runtime\":\"qwenpaw\","
                        + "\"image\":\"example/worker@sha256:old\",\"replicas\":1,\"env\":{}}",
                "rollout-1", 1, "operator", Instant.parse("2030-01-01T00:02:00Z"), "correlation-1",
                Instant.parse("2030-01-01T00:00:00Z"));
        operation = new WorkerOperation(operation.id(), operation.agentId(), operation.type(),
                WorkerOperationStatus.FAILED, operation.requestedSpecDigest(), operation.requestedRuntime(),
                operation.requestedConfigRevision(), operation.requestedSecretGeneration(), operation.previousStableSpec(),
                operation.idempotencyKey(), operation.expectedAgentVersion(), operation.owner(), operation.leaseExpiresAt(),
                "OPERATION_LEASE_EXPIRED", operation.correlationId(), operation.createdAt(), operation.updatedAt(), 3);
        when(operations.failed(eq(agentId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(operation));

        mockMvc.perform(get("/internal/v1/worker-operations/failed/{agentId}", agentId)
                        .header(InternalWorkerOperationController.TOKEN_HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operation.id().toString()))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.previousStableSpec").value(operation.previousStableSpec()));
    }

    @Test
    void confirmsAutomaticRollbackThroughTheInternalBoundary() throws Exception {
        UUID operationId = UUID.randomUUID();
        WorkerOperation operation = WorkerOperation.pending(operationId, UUID.randomUUID(), WorkerOperationType.ROLLOUT,
                "sha256:new", "qwenpaw", "config-2", "secret-2", "{}", "rollout-1", 1,
                "operator", Instant.parse("2030-01-01T00:02:00Z"), "correlation-1",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(operations.rollback(null, operationId, 3L, "rollback-key")).thenReturn(operation);

        mockMvc.perform(post("/internal/v1/worker-operations/{id}/rollback", operationId)
                .header(InternalWorkerOperationController.TOKEN_HEADER, "secret")
                        .header("Idempotency-Key", "rollback-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operationId.toString()));

        verify(operations).rollback(null, operationId, 3L, "rollback-key");
    }
}
