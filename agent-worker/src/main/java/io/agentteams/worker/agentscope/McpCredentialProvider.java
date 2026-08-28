package io.agentteams.worker.agentscope;

import java.util.Optional;

/** Resolves a short-lived MCP credential without putting its value in runtime configuration. */
@FunctionalInterface
public interface McpCredentialProvider {
    Optional<String> resolve(String credentialRef);
}
