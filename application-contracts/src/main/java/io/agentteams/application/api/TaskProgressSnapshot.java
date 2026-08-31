package io.agentteams.application.api;

/** Immutable progress projection for a task run. */
public record TaskProgressSnapshot(String phase, long completed, long total,
        int progress, String waitingReason) {

    public TaskProgressSnapshot {
        phase = requireText(phase, "phase");
        waitingReason = waitingReason == null || waitingReason.isBlank() ? "" : waitingReason.trim();
        if (completed < 0) {
            throw new IllegalArgumentException("completed must not be negative");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (completed > total) {
            throw new IllegalArgumentException("completed must not exceed total");
        }
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("progress must be between 0 and 100");
        }
        if (total == 0 && progress != 0) {
            throw new IllegalArgumentException("progress must be zero when total is zero");
        }
    }

    public long completedCount() {
        return completed;
    }

    public long totalCount() {
        return total;
    }

    public int progressPercent() {
        return progress;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
