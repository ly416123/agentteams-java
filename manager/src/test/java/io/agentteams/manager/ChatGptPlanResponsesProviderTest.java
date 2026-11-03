package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptPlanResponsesProviderTest {
    @TempDir Path tempDir;

    @Test
    void usesOAuthResponsesStreamingAndReturnsText() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            request.set(exchange.getRequestHeaders().getFirst("Authorization") + "\n"
                    + new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}\n\n"
                    + "data: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Path tokenFile = tempDir.resolve("token");
            Files.writeString(tokenFile, "oauth-session-token\n");
            var provider = new ChatGptPlanResponsesProvider("gpt-5.6-luna", tokenFile,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"),
                    HttpClient.newHttpClient(), Duration.ofSeconds(5));

            var result = provider.complete(new ModelProvider.ModelRequest("say hello", 42));

            assertThat(result.content()).isEqualTo("hello");
            assertThat(result.model()).isEqualTo("gpt-5.6-luna");
            assertThat(result.promptTokens()).isEqualTo(5);
            assertThat(result.completionTokens()).isEqualTo(2);
            assertThat(request.get()).contains("Bearer oauth-session-token", "\"store\":false",
                    "\"stream\":true", "\"max_output_tokens\":42", "\"input_text\"", "gpt-5.6-luna");
        } finally {
            server.stop(0);
        }
    }
}
