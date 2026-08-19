package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigManifestFetcherTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesUrnManifestAndValidatesChecksumAndSize() throws Exception {
        String manifest = "{\"model\":\"deepseek\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config/snapshots/snapshot-1/manifest", exchange -> {
            byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ConfigManifestFetcher fetcher = new ConfigManifestFetcher(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2), 1024);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));

        assertThat(fetcher.fetch("urn:agentteams:config:snapshot-1", "snapshot-1", checksum,
                manifest.getBytes(StandardCharsets.UTF_8).length)).isEqualTo(manifest);
    }

    @Test
    void rejectsChecksumMismatchAndOversizedManifest() throws Exception {
        ConfigManifestFetcher fetcher = new ConfigManifestFetcher(null, Duration.ofSeconds(1), 4);

        assertThatThrownBy(() -> fetcher.fetch("https://example.test/config", "snapshot", "bad", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
        assertThatThrownBy(() -> fetcher.fetch("file:///tmp/config", "snapshot", "bad", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }
}
