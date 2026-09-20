package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.agentteams.contracts.v1.ConfigChanged;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConfigBootstrapFetcherTest {
    private static final String AGENT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String BINDING_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SNAPSHOT_ID = "00000000-0000-0000-0000-000000000003";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void convertsCurrentBindingResponseToConfigChanged() throws Exception {
        String manifest = "{\"model\":\"qwen\"}";
        String checksum = sha256(manifest);
        String body = "[{\"bindingId\":\"" + BINDING_ID + "\",\"agentId\":\"" + AGENT_ID
                + "\",\"snapshotId\":\"" + SNAPSHOT_ID + "\",\"configVersion\":7"
                + ",\"manifestUri\":\"urn:agentteams:config:" + SNAPSHOT_ID + "\",\"manifestSha256\":\""
                + checksum + "\",\"sizeBytes\":" + manifest.getBytes(StandardCharsets.UTF_8).length
                + ",\"manifestJson\":\"" + manifest.replace("\"", "\\\"")
                + "\",\"files\":[{\"path\":\"models/provider.json\",\"uri\":\"urn:agentteams:config-file:"
                + SNAPSHOT_ID + ":models/provider.json\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"sizeBytes\":12,\"contentType\":\"application/json\"}],\"rollback\":false}]";
        start(body, 200);

        ConfigChanged changed = new ConfigBootstrapFetcher(base(), Duration.ofSeconds(2), 4096, 1024)
                .fetch(AGENT_ID).getFirst();

        assertThat(changed.getBindingId()).isEqualTo(BINDING_ID);
        assertThat(changed.getSnapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(changed.getConfigVersion()).isEqualTo(7);
        assertThat(changed.getManifestJson()).isEqualTo(manifest);
        assertThat(changed.getManifestSha256()).isEqualTo(checksum);
        assertThat(changed.getFiles(0).getUri()).startsWith("urn:agentteams:config-file:");
        assertThat(changed.getMetadata().getAgentId()).isEqualTo(AGENT_ID);
        assertThat(changed.getMetadata().getSequence()).isZero();
    }

    @Test
    void returnsEmptyListForNoCurrentBindings() throws Exception {
        start("[]", 200);

        assertThat(new ConfigBootstrapFetcher(base(), Duration.ofSeconds(2), 128, 128)
                .fetch(AGENT_ID)).isEmpty();
    }

    @Test
    void rejectsHttpErrorsOversizedResponsesAndMismatchedAgent() throws Exception {
        start("error", 500);
        assertThatThrownBy(() -> new ConfigBootstrapFetcher(base(), Duration.ofSeconds(2), 128, 128)
                .fetch(AGENT_ID)).hasMessageContaining("HTTP 500");

        stop();
        start("x".repeat(129), 200);
        assertThatThrownBy(() -> new ConfigBootstrapFetcher(base(), Duration.ofSeconds(2), 128, 128)
                .fetch(AGENT_ID)).hasMessageContaining("response exceeds");

        stop();
        String wrongAgent = "[{\"bindingId\":\"" + BINDING_ID + "\",\"agentId\":\"00000000-0000-0000-0000-000000000009"
                + "\",\"snapshotId\":\"" + SNAPSHOT_ID + "\",\"configVersion\":1,\"manifestUri\":\"urn:x\","
                + "\"manifestSha256\":\"" + "a".repeat(64) + "\",\"sizeBytes\":0,\"files\":[]}]";
        start(wrongAgent, 200);
        assertThatThrownBy(() -> new ConfigBootstrapFetcher(base(), Duration.ofSeconds(2), 4096, 128)
                .fetch(AGENT_ID)).hasMessageContaining("agent_id");
    }

    private void start(String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config/agents/" + AGENT_ID + "/current", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
