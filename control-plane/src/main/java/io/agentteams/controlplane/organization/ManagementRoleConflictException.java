package io.agentteams.controlplane.organization;

/** Stable conflict code for versioned management role lifecycle transitions. */
public final class ManagementRoleConflictException extends RuntimeException {
    private final String code;

    public ManagementRoleConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
