package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpRuntimeMetadataTest {
    @Test
    void acceptsNonSecretHttpRuntimeMetadata() {
        McpRuntimeMetadata metadata = new McpRuntimeMetadata("2d85e034-1486-4df0-b4b9-6d8e622ace61",
                "STREAMABLE_HTTP", "https://mcp.example.test/http", "MCP_SERVER_TOKEN");

        assertThat(metadata.serverId()).isEqualTo("2d85e034-1486-4df0-b4b9-6d8e622ace61");
        assertThat(metadata.transport()).isEqualTo("STREAMABLE_HTTP");
        assertThat(metadata.credentialRef()).isEqualTo("MCP_SERVER_TOKEN");
    }

    @Test
    void rejectsSecretBearingOrUnsupportedMetadata() {
        assertThatThrownBy(() -> new McpRuntimeMetadata("server-a", "STDIO",
                "https://mcp.example.test/http", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRuntimeMetadata("server-a", "SSE",
                "https://user:password@mcp.example.test/sse", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRuntimeMetadata("server-a", "SSE",
                "https://mcp.example.test/sse?token=secret", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpRuntimeMetadata("server-a", "SSE",
                "https://mcp.example.test/sse", "not-an-env-ref"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
