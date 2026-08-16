package io.agentteams.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;

public class OpenAICompatibleProvider implements ModelProvider {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final RetryPolicy retryPolicy;
    private final RetrySleeper sleeper;

    public OpenAICompatibleProvider(URI endpoint, String apiKey, String model, HttpClient client,
            ObjectMapper mapper, Duration timeout) {
        this(endpoint, apiKey, model, client, mapper, timeout, RetryPolicy.defaults(), RetrySleeper.system());
    }

    public OpenAICompatibleProvider(URI endpoint, String apiKey, String model, HttpClient client,
            ObjectMapper mapper, Duration timeout, RetryPolicy retryPolicy, RetrySleeper sleeper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        int retries = 0;
        while (true) {
            try {
                return sendOnce(request);
            } catch (ModelProviderException error) {
                if (!error.retryable() || retries >= retryPolicy.maxRetries()) throw error;
                retries++;
                try {
                    sleeper.sleep(retryPolicy.backoffForRetry(retries));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ModelProviderException("model provider retry interrupted",
                            ModelProviderException.Category.INTERRUPTED, false, -1, interrupted);
                }
            }
        }
    }

    @Override
    public String providerName() { return "openai-compatible"; }

    @Override
    public String modelName() { return model; }

    private ModelResponse sendOnce(ModelRequest request) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model).put("max_tokens", request.maxTokens());
        payload.putArray("messages").addObject().put("role", "user").put("content", request.prompt());
        String body = payload.toString();
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response;
        try {
            response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException error) {
            throw new ModelProviderException("model provider request timed out",
                    ModelProviderException.Category.TIMEOUT, true, -1, error);
        } catch (IOException error) {
            throw new ModelProviderException("model provider request failed",
                    ModelProviderException.Category.NETWORK, true, -1, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("model provider request interrupted",
                    ModelProviderException.Category.INTERRUPTED, false, -1, error);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) throw httpError(status);

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException error) {
            throw new ModelProviderException("model provider returned malformed JSON",
                    ModelProviderException.Category.PROTOCOL, false, status, error);
        }
        if (root == null || !root.isObject()) {
            throw new ModelProviderException("model provider returned a non-object response",
                    ModelProviderException.Category.PROTOCOL, false, status, null);
        }
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (content.isBlank()) {
            throw new ModelProviderException("model response contains no content",
                    ModelProviderException.Category.PROTOCOL, false, status, null);
        }
        return new ModelResponse(content, root.path("model").asText(model),
                root.path("usage").path("prompt_tokens").asLong(0),
                root.path("usage").path("completion_tokens").asLong(0));
    }

    private static ModelProviderException httpError(int status) {
        if (status == 408) {
            return new ModelProviderException("model provider request timed out with HTTP 408",
                    ModelProviderException.Category.TIMEOUT, true, status, null);
        }
        if (status == 429) {
            return new ModelProviderException("model provider rate limited the request",
                    ModelProviderException.Category.RATE_LIMITED, true, status, null);
        }
        if (status >= 500 && status <= 599) {
            return new ModelProviderException("model provider returned HTTP " + status,
                    ModelProviderException.Category.SERVER, true, status, null);
        }
        if (status == 401 || status == 403) {
            return new ModelProviderException("model provider authentication failed",
                    ModelProviderException.Category.AUTHENTICATION, false, status, null);
        }
        if (status >= 400 && status <= 499) {
            return new ModelProviderException("model provider rejected the request with HTTP " + status,
                    ModelProviderException.Category.CLIENT_ERROR, false, status, null);
        }
        return new ModelProviderException("model provider returned unexpected HTTP " + status,
                ModelProviderException.Category.UNKNOWN, false, status, null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
