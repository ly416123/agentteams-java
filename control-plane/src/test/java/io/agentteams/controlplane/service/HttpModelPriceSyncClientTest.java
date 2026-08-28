package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpModelPriceSyncClientTest {
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesBoundedSnapshotWithoutLoggingOrTrustingScopeFromPayload() throws Exception {
        server.createContext("/prices", exchange -> {
            byte[] body = "{\"sourceVersion\":\"catalog-1\",\"prices\":[{\"provider\":\"openai\",\"model\":\"gpt-5\",\"currency\":\"usd\",\"inputPricePerMillionTokens\":\"1.25\",\"outputPricePerMillionTokens\":10,\"effectiveFrom\":\"2026-08-28T00:00:00Z\"}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ModelPriceSyncProperties properties = new ModelPriceSyncProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/prices"));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setMaxResponseBytes(4096);
        properties.setMaxQuotes(10);

        ModelPriceSyncPort.Snapshot snapshot = new HttpModelPriceSyncClient(HttpClient.newHttpClient(),
                new ObjectMapper(), properties).fetch();

        assertThat(snapshot.sourceVersion()).isEqualTo("catalog-1");
        assertThat(snapshot.quotes()).singleElement().satisfies(quote -> {
            assertThat(quote.currency()).isEqualTo("USD");
            assertThat(quote.inputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("1.25"));
            assertThat(quote.outputPricePerMillionTokens()).isEqualByComparingTo(new BigDecimal("10"));
            assertThat(quote.effectiveFrom()).isEqualTo(Instant.parse("2026-08-28T00:00:00Z"));
        });
    }

    @Test
    void rejectsNonSuccessResponse() throws Exception {
        server.createContext("/prices", exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
        });
        server.start();
        ModelPriceSyncProperties properties = new ModelPriceSyncProperties();
        properties.setEndpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/prices"));

        assertThatThrownBy(() -> new HttpModelPriceSyncClient(HttpClient.newHttpClient(), new ObjectMapper(), properties)
                .fetch()).isInstanceOf(ModelPriceSyncException.class).hasMessageContaining("status 503");
    }
}
