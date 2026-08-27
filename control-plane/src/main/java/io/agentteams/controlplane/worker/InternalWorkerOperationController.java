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

    private void authorize(String token) {
        byte[] expected = internalToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (internalToken.isBlank() || !MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal worker operation token rejected");
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
}
