package io.agentteams.controlplane.team;

public final class TeamRevisionConflictException extends IllegalStateException {
    public TeamRevisionConflictException(String message) {
        super(message);
    }
}
