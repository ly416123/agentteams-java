package io.agentteams.controlplane.worker;

import java.time.Instant;
import java.util.Objects;

/** Immutable facts reported independently by Operator and Gateway after a rollout. */
public record WorkerRolloutConfirmation(
        boolean operatorReady,
        String operatorSpecDigest,
        String operatorRuntime,
        String operatorConfigRevision,
        String operatorSecretGeneration,
        boolean gatewayOnline,
        String gatewaySpecDigest,
        String gatewayRuntime,
        String gatewayConfigRevision,
        String gatewaySecretGeneration,
        Instant observedAt) {

    public WorkerRolloutConfirmation {
        operatorSpecDigest = textOrEmpty(operatorSpecDigest);
        operatorRuntime = textOrEmpty(operatorRuntime);
        operatorConfigRevision = textOrEmpty(operatorConfigRevision);
        operatorSecretGeneration = textOrEmpty(operatorSecretGeneration);
        gatewaySpecDigest = textOrEmpty(gatewaySpecDigest);
        gatewayRuntime = textOrEmpty(gatewayRuntime);
        gatewayConfigRevision = textOrEmpty(gatewayConfigRevision);
        gatewaySecretGeneration = textOrEmpty(gatewaySecretGeneration);
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public boolean matches(WorkerOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.type() != WorkerOperationType.ROLLOUT || !operatorReady || !gatewayOnline) {
            return false;
        }
        return same(operation.requestedSpecDigest(), operatorSpecDigest)
                && same(operation.requestedRuntime(), operatorRuntime)
                && same(operation.requestedConfigRevision(), operatorConfigRevision)
                && same(operation.requestedSecretGeneration(), operatorSecretGeneration)
                && same(operation.requestedSpecDigest(), gatewaySpecDigest)
                && same(operation.requestedRuntime(), gatewayRuntime)
                && same(operation.requestedConfigRevision(), gatewayConfigRevision)
                && same(operation.requestedSecretGeneration(), gatewaySecretGeneration);
    }

    private static boolean same(String expected, String observed) {
        return Objects.equals(expected, observed);
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
