package io.agentteams.controlplane.config;

/** Maps worker error text to a bounded set of dashboard-friendly failure classes. */
public final class ConfigFailureClassifier {
    private ConfigFailureClassifier() {
    }

    public static String classify(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        String value = errorMessage.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("timeout") || value.contains("timed out")) return "TIMEOUT";
        if (value.contains("permission") || value.contains("unauthorized") || value.contains("forbidden")) {
            return "AUTHORIZATION";
        }
        if (value.contains("checksum") || value.contains("validation") || value.contains("invalid")) {
            return "VALIDATION";
        }
        if (value.contains("unsupported") || value.contains("not supported")) return "UNSUPPORTED";
        return "WORKER_ERROR";
    }
}
