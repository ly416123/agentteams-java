package io.agentteams.controlplane.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;

/**
 * Basic production HTTP connector for MCP Streamable HTTP and legacy SSE endpoints.
 *
 * <p>The connector is deliberately credential-blind: its SPI target has no credential field and
 * this implementation never resolves, logs, or adds an authorization header. It is opt-in via
 * {@link McpHttpConnectorConfiguration}.</p>
 */
public final class McpHttpTransportConnector implements McpTransportConnector {
    private static final String ACCEPT = "application/json, text/event-stream";
    private final HttpClient httpClient;
    private final McpHttpConnectorProperties properties;
    private final ObjectMapper objectMapper;
    private final McpToolsListSchemaValidator toolsValidator;
    private final AtomicLong requestIds = new AtomicLong();

    public McpHttpTransportConnector(HttpClient httpClient, McpHttpConnectorProperties properties) {
        this(httpClient, properties, new ObjectMapper());
    }

    public McpHttpTransportConnector(HttpClient httpClient, McpHttpConnectorProperties properties,
            ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.toolsValidator = new McpToolsListSchemaValidator(objectMapper);
        properties.validate();
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("MCP HTTP client must disable redirects");
        }
    }

    @Override
    public McpTransport transport() {
        return McpTransport.STREAMABLE_HTTP;
    }

    @Override
    public boolean supports(McpTransport transport) {
        return transport == McpTransport.SSE || transport == McpTransport.STREAMABLE_HTTP;
    }

    @Override
    public List<McpToolDescriptor> discoverTools(McpConnectorTarget target, Duration timeout) {
        JsonNode response = exchangeEnvelope(target, "tools/list", Map.of(), timeout);
        return toolsValidator.validateResult(response);
    }

    @Override
    public Object callTool(McpConnectorTarget target, String toolName, Map<String, Object> arguments,
            Duration timeout) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName is required");
        }
        JsonNode response = exchange(target, "tools/call", Map.of(
                "name", toolName.trim(), "arguments", arguments == null ? Map.of() : arguments), timeout);
        return toJavaValue(response);
    }

    private JsonNode exchange(McpConnectorTarget target, String method, Map<String, Object> params,
            Duration timeout) {
        JsonNode envelope = exchangeEnvelope(target, method, params, timeout);
        return envelope.get("result");
    }

    private JsonNode exchangeEnvelope(McpConnectorTarget target, String method, Map<String, Object> params,
            Duration timeout) {
        URI endpoint = validateTarget(target, timeout);
        long id = requestIds.incrementAndGet();
        String body = requestBody(id, method, params);
        if (target.transport() == McpTransport.SSE) {
            return exchangeSse(endpoint, id, body, timeout);
        }
        HttpRequest request = request(endpoint, body, timeout);
        try {
            HttpResponse<String> response = httpClient.send(request, bodyHandler());
            return parseEnvelope(classify(response), id);
        } catch (McpHttpConnectorException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw network(McpHttpFailureCategory.CONNECTION_FAILURE, "HTTP request interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw classifyNetwork(error);
        }
    }

    private JsonNode exchangeSse(URI endpoint, long id, String body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Accept", "text/event-stream")
                .GET().build();
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = await(future, timeout);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            close(response.body());
            throw statusFailure(response.statusCode());
        }
        if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT)
                .startsWith("text/event-stream")) {
            close(response.body());
            throw protocol("SSE endpoint did not return text/event-stream", null);
        }

        SseReader reader = new SseReader(response.body(), endpoint, id);
        reader.start();
        try {
            URI messageEndpoint = validateEndpoint(await(reader.endpoint, timeout));
            HttpResponse<String> post = httpClient.send(request(messageEndpoint, body, timeout), bodyHandler());
            McpHttpResponse postResponse = classify(post);
            if (postResponse.body().isBlank()) {
                return parseEnvelope(new McpHttpResponse(200, "application/json",
                        await(reader.message, timeout)), id);
            }
            return parseEnvelope(postResponse, id);
        } catch (McpHttpConnectorException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw network(McpHttpFailureCategory.CONNECTION_FAILURE, "SSE request interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw classifyNetwork(error);
        } finally {
            close(response.body());
            reader.cancel();
            future.cancel(true);
        }
    }

    private URI validateTarget(McpConnectorTarget target, Duration timeout) {
        Objects.requireNonNull(target, "target");
        if (!supports(target.transport())) {
            throw new McpHttpConnectorException(McpHttpFailureCategory.PROTOCOL_ERROR,
                    "MCP HTTP connector does not support the requested transport");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(properties.getMaxTimeout()) > 0) {
            throw new McpHttpConnectorException(McpHttpFailureCategory.TIMEOUT,
                    "MCP HTTP timeout is outside the configured limit");
        }
        URI uri = target.endpoint();
        return validateEndpoint(uri);
    }

    private URI validateEndpoint(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = properties.getAllowedSchemes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(scheme::equals);
        boolean allowlisted = !properties.getAllowedEndpoints().isEmpty()
                ? properties.getAllowedEndpoints().stream().anyMatch(value -> value.equals(uri.toString()))
                : properties.getAllowedHosts().stream().anyMatch(pattern -> hostMatches(host, pattern));
        if (!"http".equals(scheme) && !"https".equals(scheme) || host.isBlank()
                || uri.getUserInfo() != null || uri.getFragment() != null || !schemeAllowed || !allowlisted) {
            throw new McpHttpConnectorException(McpHttpFailureCategory.ENDPOINT_NOT_ALLOWED,
                    "MCP HTTP endpoint is not allowlisted");
        }
        return uri;
    }

    private static boolean hostMatches(String host, String pattern) {
        String value = pattern.trim().toLowerCase(Locale.ROOT);
        return "*".equals(value) || host.equals(value)
                || value.startsWith("*.") && host.endsWith(value.substring(1));
    }

    private HttpRequest request(URI endpoint, String body, Duration timeout) {
        return HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Accept", ACCEPT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private String requestBody(long id, String method, Map<String, Object> params) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("jsonrpc", "2.0");
            root.put("id", id);
            root.put("method", method);
            root.set("params", objectMapper.valueToTree(params));
            return objectMapper.writeValueAsString(root);
        } catch (RuntimeException | IOException error) {
            throw protocol("MCP request could not be encoded", error);
        }
    }

    private McpHttpResponse classify(HttpResponse<String> response) {
        return new McpHttpResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    private JsonNode parseEnvelope(McpHttpResponse response, long id) {
        if (!response.isSuccessful()) throw statusFailure(response.statusCode());
        String json = response.isEventStream() ? firstSseData(response.body()) : response.body();
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || !"2.0".equals(root.path("jsonrpc").asText())
                    || !root.has("id") || root.path("id").asLong(Long.MIN_VALUE) != id) {
                throw protocol("MCP response has an invalid JSON-RPC envelope", null);
            }
            if (root.has("error")) throw protocol("MCP response contains a JSON-RPC error", null);
            JsonNode result = root.get("result");
            if (result == null) throw protocol("MCP response result is missing", null);
            return result;
        } catch (McpHttpConnectorException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw protocol("MCP response is not valid JSON", error);
        }
    }

    private static String firstSseData(String body) {
        for (String event : body.split("\\n\\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\\n")) {
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            if (data.length() > 0) return data.toString();
        }
        throw protocol("MCP SSE response did not contain data", null);
    }

    private static McpHttpConnectorException statusFailure(int status) {
        McpHttpFailureCategory category = status == 401 ? McpHttpFailureCategory.UNAUTHORIZED
                : status == 403 ? McpHttpFailureCategory.FORBIDDEN
                : status == 429 ? McpHttpFailureCategory.RATE_LIMITED
                : status >= 500 && status < 600 ? McpHttpFailureCategory.UPSTREAM_5XX
                : status >= 300 && status < 400 ? McpHttpFailureCategory.REDIRECT_NOT_ALLOWED
                : McpHttpFailureCategory.HTTP_ERROR;
        return new McpHttpConnectorException(category, status, "MCP HTTP response was not successful");
    }

    private <T> T await(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new McpHttpConnectorException(McpHttpFailureCategory.TIMEOUT, "MCP HTTP request timed out", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw network(McpHttpFailureCategory.CONNECTION_FAILURE, "MCP HTTP request interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof McpHttpConnectorException connectorException) throw connectorException;
            throw classifyNetwork(cause);
        }
    }

    private HttpResponse.BodyHandler<String> bodyHandler() {
        return info -> HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(), bytes -> {
            if (bytes.length > properties.getMaxResponseBytes()) {
                throw protocol("MCP HTTP response exceeds the configured limit", null);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        });
    }

    private static Object toJavaValue(JsonNode value) {
        if (value.isObject()) {
            return new ObjectMapper().convertValue(value, Map.class);
        }
        if (value.isArray()) return new ObjectMapper().convertValue(value, List.class);
        if (value.isNull()) return null;
        return value.isBoolean() ? value.booleanValue() : value.isNumber() ? value.numberValue() : value.textValue();
    }

    private static McpHttpConnectorException classifyNetwork(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null
                && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof ExecutionException)) cause = cause.getCause();
        if (cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
            return network(McpHttpFailureCategory.TIMEOUT, "MCP HTTP request timed out", cause);
        }
        if (cause instanceof UnknownHostException) {
            return network(McpHttpFailureCategory.DNS_FAILURE, "MCP HTTP DNS lookup failed", cause);
        }
        if (cause instanceof SSLException) {
            return network(McpHttpFailureCategory.TLS_FAILURE, "MCP HTTP TLS negotiation failed", cause);
        }
        return network(cause instanceof ConnectException ? McpHttpFailureCategory.CONNECTION_FAILURE
                : McpHttpFailureCategory.CONNECTION_FAILURE, "MCP HTTP network request failed", cause);
    }

    private static McpHttpConnectorException network(McpHttpFailureCategory category, String message,
            Throwable cause) {
        return new McpHttpConnectorException(category, message, cause);
    }

    private static McpHttpConnectorException protocol(String message, Throwable cause) {
        return new McpHttpConnectorException(McpHttpFailureCategory.PROTOCOL_ERROR, message, cause);
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) { }
    }

    private static final class SseReader {
        private final InputStream input;
        private final URI base;
        private final long id;
        private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
        private final CompletableFuture<String> message = new CompletableFuture<>();
        private CompletableFuture<?> task;

        private SseReader(InputStream input, URI base, long id) {
            this.input = input;
            this.base = base;
            this.id = id;
        }

        private void start() {
            task = CompletableFuture.runAsync(this::read);
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        emit(event, data.toString());
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    }
                }
                if (!endpoint.isDone()) endpoint.completeExceptionally(protocol("SSE stream ended before endpoint", null));
                if (!message.isDone()) message.completeExceptionally(protocol("SSE stream ended before response", null));
            } catch (IOException error) {
                endpoint.completeExceptionally(error);
                message.completeExceptionally(error);
            }
        }

        private void emit(String event, String data) {
            if (data.isBlank()) return;
            if ("endpoint".equals(event) && !endpoint.isDone()) {
                try { endpoint.complete(base.resolve(URI.create(data))); }
                catch (RuntimeException error) { endpoint.completeExceptionally(protocol("SSE endpoint is invalid", error)); }
                return;
            }
            if (!message.isDone()) {
                try {
                    JsonNode root = new ObjectMapper().readTree(data);
                    if (root != null && root.path("id").asLong(Long.MIN_VALUE) == id) message.complete(data);
                } catch (IOException ignored) { }
            }
        }

        private void cancel() { if (task != null) task.cancel(true); }
    }
}
