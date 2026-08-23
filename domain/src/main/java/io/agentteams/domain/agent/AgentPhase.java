package io.agentteams.domain.agent;

/** Lifecycle phases for an Agent as observed by the control plane. */
public enum AgentPhase {
    PROVISIONING,
    READY,
    BUSY,
    DRAINING,
    TERMINATED,
    OFFLINE,
    FAILED;

    public boolean acceptsTasks() {
        return this == READY;
    }
}
