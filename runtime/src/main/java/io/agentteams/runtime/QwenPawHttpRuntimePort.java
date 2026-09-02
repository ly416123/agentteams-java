package io.agentteams.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QwenPaw process-port implementation backed by the official HTTP/SSE API.
 *
 * <p>The port sends {@code POST /api/console/chat} requests and publishes only
 * terminal SSE events because {@link RuntimeResultSink} currently represents
 * task completion rather than progress updates.</p>
 */
public final class QwenPawHttpRuntimePort implements QwenPawProcessPort {
    private final QwenPawHttpRuntimeConfiguration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<UUID, RequestHandle> requests = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();

    private volatile boolean started;
    private volatile RuntimeResultSink resultSink;
    private volatile Clock clock;
    private volatile ExecutorService readerExecutor;

    public QwenPawHttpRuntimePort(QwenPawHttpRuntimeConfiguration configuration) {
        this(configuration,
                HttpClient.newBuilder().connectTimeout(configuration.connectTimeout()).build(),
                new ObjectMapper());
    }

    QwenPawHttpRuntimePort(QwenPawHttpRuntimeConfiguration configuration,
            HttpClient httpClient, ObjectMapper objectMapper) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void start(AgentRuntimeContext context, RuntimeResultSink resultSink) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resultSink, "resultSink");
        synchronized (lifecycleMonitor) {
            if (started) {
                throw new IllegalStateException("QwenPaw HTTP port is already started");
            }
            this.clock = context.clock();
            this.resultSink = resultSink;
            this.readerExecutor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "qwenpaw-http-sse-reader");
                thread.setDaemon(true);
                return thread;
            });
            this.started = true;
        }
    }

    @Override
    public void submit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        RuntimeResultSink sink;
        ExecutorService executor;
        synchronized (lifecycleMonitor) {
            if (!started) {
                throw new IllegalStateException("QwenPaw HTTP port is not started");
            }
            sink = resultSink;
            executor = readerExecutor;
        }

        RequestHandle handle = new RequestHandle(task.id());
        if (requests.putIfAbsent(task.id(), handle) != null) {
            throw new IllegalStateException("task is already in flight: " + task.id());
        }

        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(chatEndpoint())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Id", configuration.agentId());
            String authorizationToken = configuration.authorizationToken();
            if (authorizationToken != null && !authorizationToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + authorizationToken);
            }
            request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(task), StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<InputStream>> response = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            handle.future = response;
            response.thenAcceptAsync(value -> processResponse(task, handle, value, sink), executor)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            publishFailure(task, handle, sink, "QwenPaw HTTP request failed: " + rootMessage(error), null);
                        }
                    });
        } catch (IOException | RuntimeException error) {
            requests.remove(task.id(), handle);
            throw error instanceof RuntimeException runtimeException
                    ? runtimeException : new IllegalStateException("unable to encode QwenPaw request", error);
        }
    }

    @Override
    public void applyConfig(RuntimeConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lifecycleMonitor) {
            if (!started) {
                throw new IllegalStateException("QwenPaw HTTP port is not started");
            }
        }
        if ("/api/models/active".equals(configuration.configurationPath())) {
            applyNativeModelConfig(snapshot);
            return;
        }
        applyLegacyConfig(snapshot);
    }

    private void applyNativeModelConfig(RuntimeConfigSnapshot snapshot) {
        Map<String, String> values = snapshot.values();
        String model = values.get("model");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("QwenPaw native configuration requires model");
        }
        String provider = values.get("provider_id");
        if (provider == null || provider.isBlank()) {
            provider = activeProviderId();
        }
        ObjectNode selection = objectMapper.createObjectNode()
                .put("provider_id", provider)
                .put("model", model)
                .put("scope", "agent")
                .put("agent_id", configuration.agentId());
        sendConfiguration("PUT", configurationEndpoint(), selection, snapshot);

        ObjectNode modelConfig = objectMapper.createObjectNode();
        values.forEach((key, value) -> {
            if (!"provider_id".equals(key) && !"model".equals(key)) {
                modelConfig.put(key, value);
            }
        });
        if (!modelConfig.isEmpty()) {
            sendConfiguration("PUT", modelConfigurationEndpoint(provider, model), modelConfig, snapshot);
        }
    }

    private String activeProviderId() {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(configurationEndpoint())
                .header("Accept", "application/json");
        addAuthorization(requestBuilder);
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("QwenPaw active model HTTP " + response.statusCode());
            }
            String provider = objectMapper.readTree(response.body()).path("active_llm").path("provider_id").asText();
            if (provider.isBlank()) {
                throw new IllegalStateException("QwenPaw active model did not include provider_id");
            }
            return provider;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("QwenPaw active model request failed", error);
        }
    }

    private void applyLegacyConfig(RuntimeConfigSnapshot snapshot) {
        ObjectNode body = objectMapper.valueToTree(snapshot.values());
        sendConfiguration("PATCH", configurationEndpoint(), body, snapshot);
    }

    private void sendConfiguration(String method, URI endpoint, ObjectNode body, RuntimeConfigSnapshot snapshot) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(configurationEndpoint())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Agent-Id", configuration.agentId())
                .header("X-Config-Version", Long.toString(snapshot.version()))
                .header("X-Config-Sha256", snapshot.checksum());
        addAuthorization(requestBuilder);
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder
                    .uri(endpoint)
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                            StandardCharsets.UTF_8))
                    .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("QwenPaw config HTTP " + response.statusCode()
                        + (response.body().isBlank() ? "" : ": " + truncate(response.body())));
            }
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("QwenPaw configuration request failed", error);
        }
    }

    @Override
    public void cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        RequestHandle handle = requests.remove(taskId);
        if (handle != null) {
            handle.cancel();
        }
    }

    @Override
    public void stop() {
        ExecutorService executor;
        synchronized (lifecycleMonitor) {
            started = false;
            executor = readerExecutor;
            readerExecutor = null;
            resultSink = null;
            clock = null;
        }
        requests.values().forEach(RequestHandle::cancel);
        requests.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void processResponse(RuntimeTask task, RequestHandle handle,
            HttpResponse<InputStream> response, RuntimeResultSink sink) {
        handle.stream = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                String detail = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                publishFailure(task, handle, sink, "QwenPaw HTTP " + response.statusCode()
                        + (detail.isBlank() ? "" : ": " + truncate(detail)), null);
            } catch (IOException error) {
                publishFailure(task, handle, sink, "QwenPaw HTTP " + response.statusCode()
                        + ": unable to read error response: " + error.getMessage(), null);
            }
            return;
        }

        String latestOutput = "";
        RuntimeCallUsage latestUsage = null;
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    SseEvent event = parseEvent(data);
                    data.setLength(0);
                    if (event == null) {
                        continue;
                    }
                    latestOutput = event.output().isBlank() ? latestOutput : event.output();
                    latestUsage = event.callUsage() == null ? latestUsage : event.callUsage();
                    if (event.terminal() && "completed".equals(event.status())) {
                        publishSuccess(task, handle, sink, latestOutput, latestUsage);
                        return;
                    }
                    if (event.terminal() && "failed".equals(event.status())) {
                        publishFailure(task, handle, sink,
                                event.error().isBlank() ? latestOutput : event.error(), latestUsage);
                        return;
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            SseEvent event = parseEvent(data);
            if (event != null) {
                latestOutput = event.output().isBlank() ? latestOutput : event.output();
                latestUsage = event.callUsage() == null ? latestUsage : event.callUsage();
                if (event.terminal() && "completed".equals(event.status())) {
                    publishSuccess(task, handle, sink, latestOutput, latestUsage);
                    return;
                }
                if (event.terminal() && "failed".equals(event.status())) {
                    publishFailure(task, handle, sink,
                            event.error().isBlank() ? latestOutput : event.error(), latestUsage);
                    return;
                }
            }
            publishFailure(task, handle, sink, "QwenPaw SSE stream ended before completion", latestUsage);
        } catch (IOException error) {
            publishFailure(task, handle, sink, "QwenPaw SSE stream failed: " + error.getMessage(), latestUsage);
        } finally {
            handle.stream = null;
        }
    }

    private void publishSuccess(RuntimeTask task, RequestHandle handle,
            RuntimeResultSink sink, String output, RuntimeCallUsage callUsage) {
        publish(task, handle, sink, RuntimeResult.success(task.id(), output, now(), callUsage));
    }

    private void publishFailure(RuntimeTask task, RequestHandle handle,
            RuntimeResultSink sink, String output, RuntimeCallUsage callUsage) {
        publish(task, handle, sink, RuntimeResult.failure(task.id(), output, now(), callUsage));
    }

    private void publish(RuntimeTask task, RequestHandle handle,
            RuntimeResultSink sink, RuntimeResult result) {
        if (requests.remove(task.id(), handle) && handle.terminal.compareAndSet(false, true)) {
            sink.accept(result);
        }
    }

    private SseEvent parseEvent(StringBuilder data) {
        if (data.isEmpty() || "[DONE]".equals(data.toString())) {
            return null;
        }
        try {
            JsonNode event = objectMapper.readTree(data.toString());
            String object = event.path("object").asText();
            boolean terminal = object.isBlank() || "response".equals(object);
            return new SseEvent(event.path("status").asText(), eventOutput(event),
                    errorText(event.path("error")), terminal, usage(event));
        } catch (IOException error) {
            return new SseEvent("failed", "", "invalid QwenPaw SSE event: " + error.getMessage(), true, null);
        }
    }

    private RuntimeCallUsage usage(JsonNode event) {
        JsonNode usage = event.path("usage");
        if (!usage.isObject()
                || (!usage.has("prompt_tokens") && !usage.has("completion_tokens"))) {
            return null;
        }
        long promptTokens = nonNegativeLong(usage.path("prompt_tokens"));
        long completionTokens = nonNegativeLong(usage.path("completion_tokens"));
        String model = event.path("model").asText(usage.path("model").asText("unknown"));
        if (model.isBlank()) {
            model = "unknown";
        }
        return new RuntimeCallUsage("qwenpaw", model, 0, promptTokens, completionTokens);
    }

    private static long nonNegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.asLong() >= 0 ? value.asLong() : 0;
    }

    private String requestBody(RuntimeTask task) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode input = body.putArray("input");
        ObjectNode message = input.addObject();
        message.put("role", "user");
        message.putArray("content").addObject().put("type", "text").put("text", task.inputJson());
        // A task may be retried with the same task ID after its Worker dies.
        // QwenPaw keeps session state by session_id, so reusing the task ID
        // would let the new attempt collide with the abandoned request.
        String sessionId = task.metadata().get("attemptId");
        body.put("session_id", sessionId == null || sessionId.isBlank()
                ? task.id().toString() : sessionId);
        body.put("user_id", task.metadata().getOrDefault("userId", configuration.userId()));
        body.put("channel", task.metadata().getOrDefault("channel", configuration.channel()));
        String memoryContext = task.metadata().get("memoryContextJson");
        if (memoryContext != null && !memoryContext.isBlank()) {
            try {
                body.set("memory_context", objectMapper.readTree(memoryContext));
            } catch (IOException | RuntimeException error) {
                throw new IOException("memory context is invalid", error);
            }
        }
        return objectMapper.writeValueAsString(body);
    }

    private URI chatEndpoint() {
        String base = configuration.endpoint().toString();
        return URI.create(base.replaceAll("/+$", "") + "/api/console/chat");
    }

    private URI configurationEndpoint() {
        String base = configuration.endpoint().toString().replaceAll("/+$", "");
        String path = configuration.configurationPath().replace("{agentId}",
                java.net.URLEncoder.encode(configuration.agentId(), StandardCharsets.UTF_8));
        return URI.create(base + path);
    }

    private URI modelConfigurationEndpoint(String provider, String model) {
        String base = configuration.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + "/api/models/"
                + java.net.URLEncoder.encode(provider, StandardCharsets.UTF_8)
                + "/models/" + java.net.URLEncoder.encode(model, StandardCharsets.UTF_8) + "/config");
    }

    private void addAuthorization(HttpRequest.Builder requestBuilder) {
        String authorizationToken = configuration.authorizationToken();
        if (authorizationToken != null && !authorizationToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + authorizationToken);
        }
    }

    private Instant now() {
        Clock currentClock = clock;
        return currentClock == null ? Clock.systemUTC().instant() : currentClock.instant();
    }

    /** Extracts the assistant message from both response snapshots and streamed content events. */
    private static String eventOutput(JsonNode event) {
        JsonNode output = event.get("output");
        if (output != null && !output.isNull()) {
            return outputText(output);
        }
        String type = event.path("type").asText();
        if ("message".equals(type)) {
            return contentText(event.path("content"));
        }
        if ("text".equals(type) && !event.path("delta").asBoolean(true)) {
            return event.path("text").asText("");
        }
        return "";
    }

    private static String outputText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        if (output.isArray()) {
            boolean hasAssistantMessage = false;
            for (JsonNode message : output) {
                if ("message".equals(message.path("type").asText())
                        && "assistant".equals(message.path("role").asText())) {
                    hasAssistantMessage = true;
                    append(text, contentText(message.path("content")));
                }
            }
            if (hasAssistantMessage) {
                return text.toString();
            }
            for (JsonNode message : output) {
                append(text, contentText(message.path("content")));
            }
        } else {
            append(text, output.asText(""));
        }
        return text.toString();
    }

    private static String contentText(JsonNode content) {
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                append(text, item.path("text").asText(""));
            }
        } else {
            append(text, content.asText(""));
        }
        return text.toString();
    }

    private static String errorText(JsonNode error) {
        if (error.isObject()) {
            String message = error.path("message").asText("");
            return message.isBlank() ? error.toString() : message;
        }
        return error.asText("");
    }

    private static void append(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(value);
        }
    }

    private static String truncate(String value) {
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record SseEvent(String status, String output, String error, boolean terminal,
            RuntimeCallUsage callUsage) {
    }

    private static final class RequestHandle {
        private final UUID taskId;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile CompletableFuture<?> future;
        private volatile InputStream stream;

        private RequestHandle(UUID taskId) {
            this.taskId = taskId;
        }

        private void cancel() {
            terminal.set(true);
            CompletableFuture<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            InputStream currentStream = stream;
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (IOException ignored) {
                    // Cancellation is best effort; the terminal state is already suppressed.
                }
            }
        }
    }
}
