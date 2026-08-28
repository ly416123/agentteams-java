package io.agentteams.worker.agentscope;

import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reads MCP credentials from environment variables named by the non-secret credentialRef. */
public final class EnvironmentMcpCredentialProvider implements McpCredentialProvider {
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final Map<String, String> environment;

    public EnvironmentMcpCredentialProvider() {
        this(System.getenv());
    }

    EnvironmentMcpCredentialProvider(Map<String, String> environment) {
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    @Override
    public Optional<String> resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) return Optional.empty();
        String reference = credentialRef.trim();
        if (!ENVIRONMENT_NAME.matcher(reference).matches()) {
            throw new IllegalArgumentException("credentialRef must be an environment variable name");
        }
        String value = environment.get(reference);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
