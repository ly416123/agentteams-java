package io.agentteams.worker.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentMcpCredentialProviderTest {
    @Test
    void resolvesOnlyTheRequestedEnvironmentReference() {
        EnvironmentMcpCredentialProvider provider = new EnvironmentMcpCredentialProvider(
                Map.of("MCP_SERVER_TOKEN", "secret-value", "OTHER_TOKEN", "other-value"));

        assertThat(provider.resolve("MCP_SERVER_TOKEN")).contains("secret-value");
        assertThat(provider.resolve("MISSING_TOKEN")).isEmpty();
    }

    @Test
    void rejectsUnsafeCredentialReferences() {
        EnvironmentMcpCredentialProvider provider = new EnvironmentMcpCredentialProvider(Map.of());

        assertThatThrownBy(() -> provider.resolve("MCP/TOKEN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.resolve(null)).isEmpty();
    }
}
