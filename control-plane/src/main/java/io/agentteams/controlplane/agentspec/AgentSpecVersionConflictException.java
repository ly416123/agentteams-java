package io.agentteams.controlplane.agentspec;

import java.util.UUID;

public final class AgentSpecVersionConflictException extends IllegalArgumentException {

    public AgentSpecVersionConflictException(UUID id, long expectedVersion) {
        super("agent spec version changed while transitioning: " + id + " (expected " + expectedVersion + ")");
    }
}
