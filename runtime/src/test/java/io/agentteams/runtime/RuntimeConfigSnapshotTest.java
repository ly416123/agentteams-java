package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeConfigSnapshotTest {
    @Test
    void carriesImmutableLocalFileReferencesAlongsideValues() {
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(3, "sha-3",
                Map.of("model", "deepseek"), Map.of("models/default.json", Path.of("/tmp/config/models/default.json")));

        assertThat(snapshot.values()).containsEntry("model", "deepseek");
        assertThat(snapshot.files()).containsEntry("models/default.json", Path.of("/tmp/config/models/default.json"));
    }

    @Test
    void carriesImmutableSkillDirectoriesAlongsideFiles() {
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(3, "sha-3",
                Map.of("model", "deepseek"), Map.of(),
                Map.of("skill-a", Path.of("/tmp/config/skills/skill-a")));

        assertThat(snapshot.skillDirectories()).containsEntry("skill-a",
                Path.of("/tmp/config/skills/skill-a"));
    }

    @Test
    void carriesValidatedMcpBindingsWithoutPuttingCredentialsInGenericValues() {
        RuntimeMcpServer server = new RuntimeMcpServer("server-a", 7, "STREAMABLE_HTTP",
                "https://mcp.example.test/http", "MCP_SERVER_TOKEN", "sha256:policy");
        RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(3, "sha-3",
                Map.of("model", "deepseek"), Map.of(), Map.of(), Map.of("MCP|server-a|7", server));

        assertThat(snapshot.mcpServers()).containsEntry("MCP|server-a|7", server);
        assertThat(snapshot.values()).doesNotContainValue("MCP_SERVER_TOKEN");
        assertThat(snapshot.mcpServers()).isUnmodifiable();
    }

    @Test
    void rejectsUnsafeMcpRuntimeMetadata() {
        assertThatThrownBy(() -> new RuntimeMcpServer("server-a", 7, "STDIO",
                "https://mcp.example.test/http", "MCP_SERVER_TOKEN", "sha256:policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeMcpServer("server-a", 7, "SSE",
                "https://user:password@mcp.example.test/sse", "MCP_SERVER_TOKEN", "sha256:policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeMcpServer("server-a", 7, "SSE",
                "https://mcp.example.test/sse#secret", "MCP_SERVER_TOKEN", "sha256:policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeMcpServer("server-a", 7, "SSE",
                "https://mcp.example.test/sse", "not-an-env-ref", "sha256:policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
