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
            request = HttpRequest.newBuilder(chatEndpoint())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Id", configuration.agentId())
                    .headers(authorizationHeaders())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(task), StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<InputStream>> response = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            handle.future = response;
            response.thenAcceptAsync(value -> processResponse(task, handle, value, sink), executor)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            publishFailure(task, handle, sink, "QwenPaw HTTP request failed: " + rootMessage(error));
                        }
                    });
        } catch (IOException | RuntimeException error) {
            requests.remove(task.id(), handle);
            throw error instanceof RuntimeException runtimeException
                    ? runtimeException : new IllegalStateException("unable to encode QwenPaw request", error);
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
                        + (detail.isBlank() ? "" : ": " + truncate(detail)));
            } catch (IOException error) {
                publishFailure(task, handle, sink, "QwenPaw HTTP " + response.statusCode()
                        + ": unable to read error response: " + error.getMessage());
            }
            return;
        }

        String latestOutput = "";
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
                    if ("completed".equals(event.status())) {
                        publishSuccess(task, handle, sink, latestOutput);
                        return;
                    }
                    if ("failed".equals(event.status())) {
                        publishFailure(task, handle, sink,
                                event.error().isBlank() ? latestOutput : event.error());
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
                if ("completed".equals(event.status())) {
                    publishSuccess(task, handle, sink, latestOutput);
                    return;
                }
                if ("failed".equals(event.status())) {
                    publishFailure(task, handle, sink,
                            event.error().isBlank() ? latestOutput : event.error());
                    return;
                }
            }
            publishFailure(task, handle, sink, "QwenPaw SSE stream ended before completion");
        } catch (IOException error) {
            publishFailure(task, handle, sink, "QwenPaw SSE stream failed: " + error.getMessage());
        } finally {
            handle.stream = null;
        }
    }

    private void publishSuccess(RuntimeTask task, RequestHandle handle,
            RuntimeResultSink sink, String output) {
        publish(task, handle, sink, RuntimeResult.success(task.id(), output, now()));
    }

    private void publishFailure(RuntimeTask task, RequestHandle handle,
            RuntimeResultSink sink, String output) {
        publish(task, handle, sink, RuntimeResult.failure(task.id(), output, now()));
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
            return new SseEvent(event.path("status").asText(), outputText(event.path("output")),
                    errorText(event.path("error")));
        } catch (IOException error) {
            return new SseEvent("failed", "", "invalid QwenPaw SSE event: " + error.getMessage());
        }
    }

    private String requestBody(RuntimeTask task) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode input = body.putArray("input");
        ObjectNode message = input.addObject();
        message.put("role", "user");
        message.putArray("content").addObject().put("type", "text").put("text", task.inputJson());
        body.put("session_id", task.id().toString());
        body.put("user_id", task.metadata().getOrDefault("userId", configuration.userId()));
        body.put("channel", task.metadata().getOrDefault("channel", configuration.channel()));
        return objectMapper.writeValueAsString(body);
    }

    private URI chatEndpoint() {
        String base = configuration.endpoint().toString();
        return URI.create(base.replaceAll("/+$", "") + "/api/console/chat");
    }

    private String[] authorizationHeaders() {
        if (configuration.authorizationToken() == null) {
            return new String[0];
        }
        return new String[] {"Authorization", "Bearer " + configuration.authorizationToken()};
    }

    private Instant now() {
        Clock currentClock = clock;
        return currentClock == null ? Clock.systemUTC().instant() : currentClock.instant();
    }

    private static String outputText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        if (output.isArray()) {
            for (JsonNode message : output) {
                JsonNode content = message.path("content");
                if (content.isArray()) {
                    for (JsonNode item : content) {
                        append(text, item.path("text").asText(""));
                    }
                } else {
                    append(text, content.asText(""));
                }
            }
        } else {
            append(text, output.asText(""));
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

    private record SseEvent(String status, String output, String error) {
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
