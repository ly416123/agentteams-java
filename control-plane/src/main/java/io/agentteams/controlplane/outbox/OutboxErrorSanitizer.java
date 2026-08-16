package io.agentteams.controlplane.outbox;

public final class OutboxErrorSanitizer {

    private OutboxErrorSanitizer() {
    }

    public static String safeFailure(Throwable failure) {
        String type = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        return type.replaceAll("[^A-Za-z0-9_.-]", "_") + ":publish_failed";
    }
}
