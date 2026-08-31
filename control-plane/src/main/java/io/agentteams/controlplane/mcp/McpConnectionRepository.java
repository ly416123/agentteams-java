package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for public and tenant-owned MCP connections. */
public interface McpConnectionRepository {
    boolean insert(McpConnection connection);

    Optional<McpConnection> findByIdempotencyKey(String idempotencyKey);

    Optional<McpConnection> find(UUID id, ExecutionContext context);

    List<McpConnection> find(ExecutionContext context);
}
