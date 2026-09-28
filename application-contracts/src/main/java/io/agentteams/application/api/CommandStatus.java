package io.agentteams.application.api;

/** Terminal classification returned by the unified command endpoint. */
public enum CommandStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
    FAILED
}
