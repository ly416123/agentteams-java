package io.agentteams.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** OAuth-backed ChatGPT Responses API client for Manager (streaming required by plan OAuth). */
public final class ChatGptPlanResponsesProvider implements ModelProvider {
    public static final String DEFAULT_MODEL = "gpt-5.6-luna";
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String model;
    private final Path tokenFile;
    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;

    public ChatGptPlanResponsesProvider(String model, Path tokenFile, URI endpoint,
            HttpClient client, Duration timeout) {
        this.model = requireText(model, "model");
        this.tokenFile = Objects.requireNonNull(tokenFile, "tokenFile");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    public static HttpClient httpClient(String proxyUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI proxy = URI.create(proxyUrl.trim());
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getPort() < 1 || proxy.getRawUserInfo() != null) {
                throw new IllegalArgumentException("ChatGPT plan proxy URL is invalid");
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        String token = readToken();
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model).put("store", false).put("stream", true)
                .put("max_output_tokens", request.maxTokens());
        body.putArray("input").addObject().put("role", "user").putArray("content")
                .addObject().put("type", "input_text").put("text", request.prompt());
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        final HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException error) {
            throw new ModelProviderException("ChatGPT Responses request timed out",
                    ModelProviderException.Category.TIMEOUT, true, -1, error);
        } catch (IOException error) {
            throw new ModelProviderException("ChatGPT Responses request failed",
                    ModelProviderException.Category.NETWORK, true, -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("ChatGPT Responses request interrupted",
                    ModelProviderException.Category.INTERRUPTED, false, -1, error);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            int status = response.statusCode();
            var category = status == 401 || status == 403 ? ModelProviderException.Category.AUTHENTICATION
                    : status == 429 ? ModelProviderException.Category.RATE_LIMITED
                    : status >= 500 ? ModelProviderException.Category.SERVER
                    : ModelProviderException.Category.CLIENT_ERROR;
            throw new ModelProviderException("ChatGPT Responses returned HTTP " + status,
                    category, status == 429 || status >= 500, status, null);
        }
        StringBuilder text = new StringBuilder();
        long promptTokens = 0;
        long completionTokens = 0;
        for (String line : response.body().split("\\R")) {
            if (!line.startsWith("data: ") || "[DONE]".equals(line.substring(6).trim())) continue;
            try {
                JsonNode event = JSON.readTree(line.substring(6));
                if ("response.output_text.delta".equals(event.path("type").asText())) {
                    text.append(event.path("delta").asText());
                } else if ("response.completed".equals(event.path("type").asText())) {
                    JsonNode completed = event.path("response");
                    JsonNode usage = completed.path("usage");
                    promptTokens = usage.path("input_tokens").asLong(0);
                    completionTokens = usage.path("output_tokens").asLong(0);
                    if (text.isEmpty()) {
                        JsonNode output = completed.path("output");
                        for (JsonNode item : output) {
                            for (JsonNode content : item.path("content")) {
                                if ("output_text".equals(content.path("type").asText())) {
                                    text.append(content.path("text").asText());
                                }
                            }
                        }
                    }
                } else if ("response.failed".equals(event.path("type").asText())) {
                    throw new ModelProviderException("ChatGPT Responses generation failed");
                }
            } catch (IOException malformed) {
                throw new ModelProviderException("ChatGPT Responses returned malformed streaming data",
                        ModelProviderException.Category.PROTOCOL, false, response.statusCode(), malformed);
            }
        }
        if (text.isEmpty()) throw new ModelProviderException("ChatGPT Responses returned no text",
                ModelProviderException.Category.PROTOCOL, false, response.statusCode(), null);
        return new ModelResponse(text.toString(), model, promptTokens, completionTokens);
    }

    @Override public String providerName() { return "chatgpt-plan"; }
    @Override public String modelName() { return model; }

    private String readToken() {
        try {
            String value = Files.readString(tokenFile).trim();
            if (value.isBlank()) throw new IOException("empty token file");
            return value;
        } catch (IOException error) {
            throw new ModelProviderException("ChatGPT OAuth access token is unavailable",
                    ModelProviderException.Category.AUTHENTICATION, false, -1, null);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
