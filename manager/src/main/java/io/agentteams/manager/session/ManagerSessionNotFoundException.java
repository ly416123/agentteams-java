package io.agentteams.manager.session;

import java.util.UUID;

public final class ManagerSessionNotFoundException extends RuntimeException {
    public ManagerSessionNotFoundException(UUID id) { super("manager session not found"); }
}
