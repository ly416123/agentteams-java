package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillArtifactFetcherTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void downloadsExplicitArtifactAndVerifiesDigestAndSize(@TempDir Path directory) throws Exception {
        byte[] content = "skill-package".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/skill.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();

        var binding = binding("http://127.0.0.1:" + server.getAddress().getPort() + "/skill.tar.gz",
                digest, content.length);
        Path stored = new SkillArtifactFetcher(Duration.ofSeconds(2), 1024)
                .fetch(binding, directory);

        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(stored.getFileName().toString()).endsWith(".tar.gz");
    }

    @Test
    void rejectsMismatchOversizeAndNonHttpArtifactWithoutLeavingFiles(@TempDir Path directory) throws Exception {
        byte[] content = "too-large".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/skill.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
        var mismatch = binding("http://127.0.0.1:" + server.getAddress().getPort() + "/skill.tar.gz",
                "sha256:" + "0".repeat(64), content.length);
        var oversized = binding(mismatch.artifactRef(), "sha256:" + "0".repeat(64), 2048);
        var fetcher = new SkillArtifactFetcher(Duration.ofSeconds(2), 1024);

        assertThatThrownBy(() -> fetcher.fetch(mismatch, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest or size mismatch");
        assertThatThrownBy(() -> fetcher.fetch(oversized, directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
        assertThatThrownBy(() -> fetcher.fetch(new ResourceBindingLoader.ResourceBinding("SKILL", "skill-a", "1",
                "sha256:" + "1".repeat(64), "file:///tmp/skill.tar.gz", 1), directory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
        try (var files = Files.walk(directory)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void loaderAcceptsOptionalArtifactAddressAndSize() throws Exception {
        var result = ResourceBindingLoader.load(new ObjectMapper().readTree("""
                {"resourceBindings":[{"type":"SKILL","reference":"skill-a","revision":"1",
                 "digest":"sha256:abc","artifactRef":"https://example.test/skill.tar.gz","sizeBytes":12}]}
                """));

        assertThat(result.successful()).isTrue();
        assertThat(result.bindings().get(0).artifactRef()).isEqualTo("https://example.test/skill.tar.gz");
        assertThat(result.bindings().get(0).sizeBytes()).isEqualTo(12);
    }

    @Test
    void resourceAckMarksExplicitArtifactAsDownloadFailureUntilFetchCompletes() {
        String manifest = "{\"resourceBindings\":[{\"type\":\"SKILL\",\"reference\":\"skill-a\","
                + "\"revision\":\"1\",\"digest\":\"sha256:abc\","
                + "\"artifactRef\":\"https://example.test/skill.tar.gz\",\"sizeBytes\":12}]}";

        assertThat(QwenPawWorker.resourceApplyResults(manifest, false).get(0).getStatus())
                .isEqualTo(io.agentteams.contracts.v1.ResourceApplyResult.Status.FAILED);
        assertThat(QwenPawWorker.resourceApplyResults(manifest, false).get(0).getFailureCategory())
                .isEqualTo("DOWNLOAD_FAILED");
        assertThat(QwenPawWorker.resourceApplyResults(manifest, true).get(0).getStatus())
                .isEqualTo(io.agentteams.contracts.v1.ResourceApplyResult.Status.APPLIED);
    }

    private static ResourceBindingLoader.ResourceBinding binding(String uri, String digest, long size) {
        return new ResourceBindingLoader.ResourceBinding("SKILL", "skill-a", "1", "sha256:" + normalize(digest),
                uri, size);
    }

    private static String normalize(String digest) {
        return digest.startsWith("sha256:") ? digest.substring(7) : digest;
    }
}
