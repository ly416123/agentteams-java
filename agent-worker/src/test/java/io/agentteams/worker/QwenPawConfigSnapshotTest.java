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
}
