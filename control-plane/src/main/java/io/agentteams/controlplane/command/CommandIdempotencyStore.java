package io.agentteams.controlplane.command;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.Optional;

/** Tenant- and command-scoped persistence boundary for unified command replay. */
public interface CommandIdempotencyStore {
    Optional<CommandIdempotencyRecord> find(ExecutionContext context, String commandType, String idempotencyKey);

    void save(ExecutionContext context, CommandIdempotencyRecord record);
}
