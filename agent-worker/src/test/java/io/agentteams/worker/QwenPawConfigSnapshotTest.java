package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.sun.net.httpserver.HttpServer;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.ConfigFile;
import io.agentteams.contracts.v1.EventMetadata;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QwenPawConfigSnapshotTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void validatesManifestAndStagesFilesBeforeRuntimeApplication(@TempDir Path directory) throws Exception {
        String manifest = "{\"model\":\"deepseek\"}";
        byte[] fileBytes = "provider-config".getBytes(StandardCharsets.UTF_8);
        String fileSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fileBytes));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider.json", exchange -> {
            exchange.sendResponseHeaders(200, fileBytes.length);
            exchange.getResponseBody().write(fileBytes);
            exchange.close();
        });
        server.start();
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1")
                        .setOccurredAt(Timestamp.getDefaultInstance()).build())
                .setConfigVersion(2)
                .setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length)
                .addFiles(ConfigFile.newBuilder().setPath("models/provider.json")
                        .setUri("http://127.0.0.1:" + server.getAddress().getPort() + "/provider.json")
                        .setSha256(fileSha).setSizeBytes(fileBytes.length).setContentType("application/json"))
                .build();

        var snapshot = QwenPawWorker.buildConfigSnapshot(changed, manifest,
                new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory);

        assertThat(snapshot.version()).isEqualTo(2);
        assertThat(snapshot.values()).containsEntry("model", "deepseek");
        assertThat(snapshot.files()).containsKey("models/provider.json");
        assertThat(java.nio.file.Files.readString(snapshot.files().get("models/provider.json")))
                .isEqualTo("provider-config");
    }

    @Test
    void rejectsInvalidResourceBindingBeforeStagingAnyFiles(@TempDir Path directory) throws Exception {
        String manifest = "{\"resourceBindings\":[{\"type\":\"MODEL\",\"reference\":\"model-a\","
                + "\"revision\":\"\",\"digest\":\"sha256:model\"}]}";
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1").build())
                .setConfigVersion(2)
                .setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length)
                .build();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> QwenPawWorker.buildConfigSnapshot(changed,
                manifest, new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory)))
                .hasMessage("RESOURCE_BINDING_INVALID: index:0=INVALID_REVISION");
        try (var files = java.nio.file.Files.list(directory)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void includesMaterializedSkillDirectoriesInTheRuntimeSnapshot(@TempDir Path directory) throws Exception {
        String manifest = "{\"resourceBindings\":[{\"type\":\"SKILL\",\"reference\":\"skill-a\","
                + "\"revision\":\"1\",\"digest\":\"sha256:abc\","
                + "\"artifactRef\":\"https://example.test/skill.zip\",\"sizeBytes\":12}]}";
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1").build())
                .setConfigVersion(2).setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length).build();
        Path expectedSkill = directory.resolve("skills").resolve("skill-a");
        SkillArtifactMaterializer materializer = new SkillArtifactMaterializer(null, 1024) {
            @Override
            Path materialize(ResourceBindingLoader.ResourceBinding binding, Path versionDirectory) {
                return expectedSkill;
            }
        };

        var snapshot = QwenPawWorker.buildConfigSnapshot(changed, manifest,
                new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory, materializer);

        assertThat(snapshot.skillDirectories()).containsEntry("SKILL|skill-a|1|sha256:abc", expectedSkill);
    }

    @Test
    void carriesSkillCapabilityPolicyIntoTheRuntimeSnapshot(@TempDir Path directory) throws Exception {
        String manifest = "{\"resourceBindings\":[{\"type\":\"SKILL\",\"reference\":\"skill-a\","
                + "\"revision\":\"1\",\"digest\":\"sha256:abc\","
                + "\"skillCapabilities\":{\"profile\":\"ISOLATED\",\"cpuMillicores\":750,"
                + "\"memoryMiB\":768,\"ephemeralStorageMiB\":2048,\"ttlSeconds\":600,"
                + "\"networkPolicy\":\"RESTRICTED\",\"allowedMcp\":[\"github\"],"
                + "\"allowedDomains\":[\"api.github.com\"],\"allowSecretReferences\":false}}]}";
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1").build())
                .setConfigVersion(2).setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length).build();

        var snapshot = QwenPawWorker.buildConfigSnapshot(changed, manifest,
                new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory);

        assertThat(snapshot.skillCapabilities()).containsKey("SKILL|skill-a|1|sha256:abc");
        assertThat(snapshot.skillCapabilities().get("SKILL|skill-a|1|sha256:abc").networkPolicy())
                .isEqualTo(io.agentteams.application.api.SandboxPolicy.NetworkPolicy.RESTRICTED);
    }

    @Test
    void includesMcpRuntimeBindingsSeparatelyFromGenericConfigurationValues(@TempDir Path directory) throws Exception {
        String manifest = "{\"model\":\"deepseek\",\"resourceBindings\":[{\"type\":\"MCP\","
                + "\"reference\":\"search\",\"revision\":\"7\",\"digest\":\"sha256:mcp\","
                + "\"serverId\":\"server-7\",\"transport\":\"STREAMABLE_HTTP\","
                + "\"endpoint\":\"https://mcp.example.test/http\",\"credentialRef\":\"MCP_SERVER_TOKEN\"}]}";
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1").build())
                .setConfigVersion(2).setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length).build();

        var snapshot = QwenPawWorker.buildConfigSnapshot(changed, manifest,
                new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory);

        assertThat(snapshot.mcpServers()).containsKey("MCP|search|7|sha256:mcp");
        assertThat(snapshot.mcpServers().get("MCP|search|7|sha256:mcp").endpoint())
                .isEqualTo("https://mcp.example.test/http");
        assertThat(snapshot.values()).doesNotContainKey("resourceBindings");
    }

    @Test
    void rejectsMcpBindingWithoutRuntimeMetadata(@TempDir Path directory) throws Exception {
        String manifest = "{\"resourceBindings\":[{\"type\":\"MCP\",\"reference\":\"search\","
                + "\"revision\":\"7\",\"digest\":\"sha256:mcp\"}]}";
        String manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setAgentId("agent-1").build())
                .setConfigVersion(2).setManifestSha256(manifestSha)
                .setSizeBytes(manifest.getBytes(StandardCharsets.UTF_8).length).build();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> QwenPawWorker.buildConfigSnapshot(changed,
                manifest, new ConfigFileFetcher(null, Duration.ofSeconds(2), 1024), directory)))
                .hasMessage("RUNTIME_UNSUPPORTED: MCP binding requires runtime metadata");
    }
}
