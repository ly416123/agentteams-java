package io.agentteams.controlplane.worker;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable independent observations made by the Operator and Gateway. */
public record WorkerOperationObservation(
        UUID operationId,
        boolean operatorReady,
        String operatorSpecDigest,
        String operatorRuntime,
        String operatorConfigRevision,
        String operatorSecretGeneration,
        Instant operatorObservedAt,
        boolean gatewayOnline,
        String gatewaySpecDigest,
        String gatewayRuntime,
        String gatewayConfigRevision,
        String gatewaySecretGeneration,
        Instant gatewayObservedAt,
        Instant updatedAt) {

    public WorkerOperationObservation {
        Objects.requireNonNull(operationId, "operationId");
        operatorSpecDigest = textOrEmpty(operatorSpecDigest);
        operatorRuntime = textOrEmpty(operatorRuntime);
        operatorConfigRevision = textOrEmpty(operatorConfigRevision);
        operatorSecretGeneration = textOrEmpty(operatorSecretGeneration);
        gatewaySpecDigest = textOrEmpty(gatewaySpecDigest);
        gatewayRuntime = textOrEmpty(gatewayRuntime);
        gatewayConfigRevision = textOrEmpty(gatewayConfigRevision);
        gatewaySecretGeneration = textOrEmpty(gatewaySecretGeneration);
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean matches(WorkerOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return operation.type() == WorkerOperationType.ROLLOUT
                && operatorReady && gatewayOnline
                && same(operation.requestedSpecDigest(), operatorSpecDigest)
                && same(operation.requestedRuntime(), operatorRuntime)
                && same(operation.requestedConfigRevision(), operatorConfigRevision)
                && same(operation.requestedSecretGeneration(), operatorSecretGeneration)
                && same(operation.requestedSpecDigest(), gatewaySpecDigest)
                && same(operation.requestedRuntime(), gatewayRuntime)
                && same(operation.requestedConfigRevision(), gatewayConfigRevision)
                && same(operation.requestedSecretGeneration(), gatewaySecretGeneration);
    }

    static WorkerOperationObservation empty(UUID operationId, Instant now) {
        return new WorkerOperationObservation(operationId, false, "", "", "", "", null,
                false, "", "", "", "", null, now);
    }

    private static boolean same(String expected, String observed) {
        return Objects.equals(expected, observed);
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
