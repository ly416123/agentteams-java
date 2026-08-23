package io.agentteams.controlplane.project;

/** Project-local roles. The order is also the permission hierarchy. */
public enum ProjectRole {
    OWNER,
    ADMIN,
    OPERATOR,
    DEVELOPER,
    VIEWER;

    public boolean atLeast(ProjectRole required) {
        if (required == null) throw new IllegalArgumentException("required role is required");
        return ordinal() <= required.ordinal();
    }
}
