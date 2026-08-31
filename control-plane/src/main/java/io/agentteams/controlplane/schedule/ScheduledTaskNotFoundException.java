package io.agentteams.controlplane.schedule;

public final class ScheduledTaskNotFoundException extends RuntimeException {
    public ScheduledTaskNotFoundException() {
        super("scheduled task was not found in the requested scope");
    }
}
