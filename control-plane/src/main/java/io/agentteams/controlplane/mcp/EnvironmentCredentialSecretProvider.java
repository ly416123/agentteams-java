package io.agentteams.controlplane.mcp;

import io.agentteams.controlplane.security.CredentialSecretProvider;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves only explicitly allowlisted environment variable references for an MCP deployment.
 *
 * <p>The value is returned to the in-process transport adapter and is never logged or persisted.
 * An empty allowlist fails closed, which keeps the generic connector credential-blind by default.</p>
 */
final class EnvironmentCredentialSecretProvider implements CredentialSecretProvider {
    private final List<String> allowedNames;
    private final Function<String, String> environment;

    EnvironmentCredentialSecretProvider(List<String> allowedNames) {
        this(allowedNames, System::getenv);
    }

    EnvironmentCredentialSecretProvider(List<String> allowedNames, Function<String, String> environment) {
        this.allowedNames = List.copyOf(Objects.requireNonNull(allowedNames, "allowedNames"));
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Optional<String> resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank() || !allowedNames.contains(credentialRef)) {
            return Optional.empty();
        }
        String value = environment.apply(credentialRef);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
