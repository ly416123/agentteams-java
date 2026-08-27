package io.agentteams.controlplane.project;

/** Stable conflict code for project membership lifecycle transitions. */
public final class ProjectMembershipConflictException extends RuntimeException {
    private final String code;

    public ProjectMembershipConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
