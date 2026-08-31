package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.ExecutionContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpGatewayRouteRepository {
    McpGatewayRoute upsert(McpGatewayRoute route);

    Optional<McpGatewayRoute> find(UUID id, ExecutionContext context);

    List<McpGatewayRoute> find(ExecutionContext context);
}
