package io.agentteams.controlplane.worker;

import io.agentteams.controlplane.api.AgentController.WorkerOperationResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated internal bridge for rollout facts from trusted adapters. */
@RestController
@RequestMapping("/internal/v1/worker-operations")
public final class InternalWorkerOperationController {
    static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final WorkerOperationService operations;
    private final String internalToken;

    public InternalWorkerOperationController(WorkerOperationService operations,
            @Value("${agentteams.quota.internal-token:}") String internalToken) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/{operationId}/confirm")
    public ResponseEntity<WorkerOperationResponse> confirm(
            @PathVariable UUID operationId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody ConfirmationRequest request) {
        authorize(token);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        WorkerOperation operation = operations.confirmRollout(operationId, request.expectedVersion(),
                request.toDomain());
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @GetMapping("/active/{agentId}")
    public ResponseEntity<ActiveOperationResponse> active(
            @PathVariable UUID agentId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return operations.active(agentId, Instant.now())
                .map(operation -> ResponseEntity.ok(ActiveOperationResponse.from(operation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/failed/{agentId}")
    public ResponseEntity<FailedOperationResponse> failed(
            @PathVariable UUID agentId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return operations.failed(agentId, Instant.now())
                .map(operation -> ResponseEntity.ok(FailedOperationResponse.from(operation)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{operationId}/rollback")
    public ResponseEntity<WorkerOperationResponse> rollback(
            @PathVariable UUID operationId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RollbackRequest request) {
        authorize(token);
        requireIdempotencyKey(idempotencyKey);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return ResponseEntity.ok(WorkerOperationResponse.from(
                operations.rollback(null, operationId, request.expectedVersion(), idempotencyKey)));
    }

    @PostMapping("/{operationId}/operator")
    public ResponseEntity<WorkerOperationResponse> confirmOperator(
            @PathVariable UUID operationId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody OperatorConfirmationRequest request) {
        authorize(token);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        WorkerOperation operation = operations.confirmOperator(operationId, request.expectedVersion(),
                request.toDomain());
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    @PostMapping("/{operationId}/gateway")
    public ResponseEntity<WorkerOperationResponse> confirmGateway(
            @PathVariable UUID operationId,
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody GatewayConfirmationRequest request) {
        authorize(token);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        WorkerOperation operation = operations.confirmGateway(operationId, request.expectedVersion(),
                request.toDomain());
        return ResponseEntity.accepted().body(WorkerOperationResponse.from(operation));
    }

    private void authorize(String token) {
        byte[] expected = internalToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (internalToken.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal worker operation token rejected");
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 255 characters");
        }
    }

    public record ConfirmationRequest(Long expectedVersion, boolean operatorReady,
            String operatorSpecDigest, String operatorRuntime, String operatorConfigRevision,
            String operatorSecretGeneration, boolean gatewayOnline, String gatewaySpecDigest,
            String gatewayRuntime, String gatewayConfigRevision, String gatewaySecretGeneration,
            Instant observedAt) {

        WorkerRolloutConfirmation toDomain() {
            if (observedAt == null) {
                throw new IllegalArgumentException("observedAt is required");
            }
            return new WorkerRolloutConfirmation(operatorReady, operatorSpecDigest, operatorRuntime,
                    operatorConfigRevision, operatorSecretGeneration, gatewayOnline, gatewaySpecDigest,
                    gatewayRuntime, gatewayConfigRevision, gatewaySecretGeneration, observedAt);
        }
    }

    public record OperatorConfirmationRequest(Long expectedVersion, boolean ready, String specDigest,
            String runtime, String configRevision, String secretGeneration, Instant observedAt) {
        WorkerOperatorObservation toDomain() {
            if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
            return new WorkerOperatorObservation(ready, specDigest, runtime, configRevision,
                    secretGeneration, observedAt);
        }
    }

    public record GatewayConfirmationRequest(Long expectedVersion, boolean online, String specDigest,
            String runtime, String configRevision, String secretGeneration, Instant observedAt) {
        WorkerGatewayObservation toDomain() {
            if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
            return new WorkerGatewayObservation(online, specDigest, runtime, configRevision,
                    secretGeneration, observedAt);
        }
    }

    public record ActiveOperationResponse(UUID id, UUID agentId, String type, String status,
            String requestedSpecDigest, String requestedRuntime, String requestedConfigRevision,
            String requestedSecretGeneration, Instant leaseExpiresAt, String correlationId,
            Instant createdAt, Instant updatedAt, long version) {
        static ActiveOperationResponse from(WorkerOperation operation) {
            return new ActiveOperationResponse(operation.id(), operation.agentId(), operation.type().name(),
                    operation.status().name(), operation.requestedSpecDigest(), operation.requestedRuntime(),
                    operation.requestedConfigRevision(), operation.requestedSecretGeneration(),
                    operation.leaseExpiresAt(), operation.correlationId(), operation.createdAt(),
                    operation.updatedAt(), operation.version());
        }
    }

    public record FailedOperationResponse(UUID id, UUID agentId, String type, String status,
            String previousStableSpec, String failureCategory, long version) {
        static FailedOperationResponse from(WorkerOperation operation) {
            return new FailedOperationResponse(operation.id(), operation.agentId(), operation.type().name(),
                    operation.status().name(), operation.previousStableSpec(), operation.failureCategory(),
                    operation.version());
        }
    }

    public record RollbackRequest(Long expectedVersion) { }
}
