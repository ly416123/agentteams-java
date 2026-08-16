package io.agentteams.runtime;

public record RuntimeSubmission(boolean accepted, RuntimeTaskState state, String reason) {
    public static RuntimeSubmission acceptedSubmission() {
        return new RuntimeSubmission(true, RuntimeTaskState.RUNNING, "accepted");
    }

    public static RuntimeSubmission rejected(String reason) {
        return new RuntimeSubmission(false, RuntimeTaskState.REJECTED, reason);
    }
}
