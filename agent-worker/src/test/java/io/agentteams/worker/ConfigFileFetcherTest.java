package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.agentteams.contracts.v1.ConfigFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileFetcherTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesUrnFileAndValidatesChecksumAndSize(@TempDir Path directory) throws Exception {
        byte[] content = "{\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config/snapshots/snapshot-1/files/content", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();

        ConfigFile file = ConfigFile.newBuilder()
                .setPath("models/default.json")
                .setUri("urn:agentteams:config-file:snapshot-1:models/default.json")
                .setSha256(checksum)
                .setSizeBytes(content.length)
                .setContentType("application/json")
                .build();
        Path stored = new ConfigFileFetcher(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), 1024).fetch(file, directory);

        assertThat(stored).isEqualTo(directory.resolve("models/default.json"));
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
    }

    @Test
    void rejectsChecksumMismatchAndTraversal(@TempDir Path directory) {
        ConfigFile badChecksum = ConfigFile.newBuilder()
                .setPath("models/default.json")
                .setUri("https://example.test/file")
                .setSha256("bad")
                .setSizeBytes(1)
                .setContentType("text/plain")
                .build();
        ConfigFile traversal = badChecksum.toBuilder().setPath("../secret").build();
        ConfigFileFetcher fetcher = new ConfigFileFetcher(null, Duration.ofSeconds(1), 10);

        assertThatThrownBy(() -> fetcher.fetch(traversal, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative");
        assertThatThrownBy(() -> fetcher.fetch(badChecksum, directory))
                .isInstanceOf(IllegalStateException.class);
    }
}
