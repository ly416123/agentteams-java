package io.agentteams.manager.conversation;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Conversation runtime adapter for QwenPaw's HTTP/SSE console API. */
public final class QwenPawConversationRuntime implements ConversationRuntimePort, AutoCloseable {
    private final ConversationRuntimeConfiguration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, RequestHandle> requests = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor;

    public QwenPawConversationRuntime(ConversationRuntimeConfiguration configuration) {
        this(configuration,
                HttpClient.newBuilder()
                        .connectTimeout(configuration.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper());
    }

    QwenPawConversationRuntime(ConversationRuntimeConfiguration configuration,
            HttpClient httpClient, ObjectMapper objectMapper) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.readerExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-conversation-sse-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(Context context) {
        Objects.requireNonNull(context, "context");
        SessionState candidate = new SessionState(context);
        SessionState state = sessions.putIfAbsent(context.sessionId(), candidate);
        if (state == null) {
            state = candidate;
        } else if (!state.context.equals(context)) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.IDEMPOTENCY_CONFLICT,
                    "conversation session already exists with different context");
        }
        synchronized (state) {
            if (!state.started && !state.cancelled) {
                state.started = true;
                append(state, "conversation.started", "{}");
            }
        }
    }

    @Override
    public void send(Message message) {
        Objects.requireNonNull(message, "message");
        SessionState state = session(message.sessionId());
        synchronized (state) {
            if (!state.started) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation has not started");
            }
            if (state.cancelled) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.CANCELLED,
                        "conversation has been cancelled");
            }
        }

        RequestHandle handle = new RequestHandle(message.sessionId());
        if (requests.putIfAbsent(message.sessionId(), handle) != null) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                    "conversation already has a request in flight");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(chatEndpoint())
                    .timeout(configuration.requestTimeout())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Id", configuration.agentId());
            if (configuration.authorizationToken() != null) {
                builder.header("Authorization", "Bearer " + configuration.authorizationToken());
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    requestBody(state.context, message), StandardCharsets.UTF_8)).build();
            CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            handle.future = future;
            future.thenAcceptAsync(response -> processResponse(state, handle, response), readerExecutor)
                    .whenComplete((ignored, error) -> {
                        if (error != null && !handle.cancelled()) {
                            publishFailure(state, handle, classifyTransport(error));
                        }
                    });
        } catch (RuntimeException error) {
            requests.remove(message.sessionId(), handle);
            throw error;
        }
    }

    @Override
    public List<ConversationEvent> events(UUID sessionId, long afterCursor) {
        if (afterCursor < 0) {
            throw new IllegalArgumentException("afterCursor must not be negative");
        }
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return state.events.stream().filter(event -> event.cursor() > afterCursor).toList();
        }
    }

    @Override
    public void cancel(UUID sessionId) {
        SessionState state = session(sessionId);
        RequestHandle handle;
        synchronized (state) {
            if (state.cancelled) {
                return;
            }
            state.cancelled = true;
            append(state, "conversation.cancelled", "{}");
            handle = requests.remove(sessionId);
        }
        if (handle != null) {
            handle.cancel();
        }
    }

    @Override
    public void close() {
        requests.values().forEach(RequestHandle::cancel);
        requests.clear();
        readerExecutor.shutdownNow();
    }

    private void processResponse(SessionState state, RequestHandle handle,
            HttpResponse<InputStream> response) {
        handle.stream = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream body = response.body()) {
                readLimited(body);
                publishFailure(state, handle, classifyHttp(response.statusCode()));
            } catch (IOException error) {
                publishFailure(state, handle, ConversationRuntimeException.Code.PROTOCOL_ERROR);
            }
            return;
        }

        StringBuilder data = new StringBuilder();
        boolean terminal = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            long bytes = 0;
            while ((line = reader.readLine()) != null) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > configuration.maxResponseBytes()) {
                    throw new ResponseTooLargeException();
                }
                if (line.isEmpty()) {
                    terminal = processSseData(state, data);
                    data.setLength(0);
                    if (terminal) {
                        return;
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (!data.isEmpty()) {
                terminal = processSseData(state, data);
            }
            if (!terminal && !handle.cancelled()) {
                publishFailure(state, handle, ConversationRuntimeException.Code.CONNECTION_CLOSED);
            }
        } catch (ResponseTooLargeException error) {
            publishFailure(state, handle, ConversationRuntimeException.Code.PROTOCOL_ERROR);
        } catch (IOException error) {
            if (!handle.cancelled()) {
                publishFailure(state, handle, ConversationRuntimeException.Code.CONNECTION_CLOSED);
            }
        } finally {
            handle.stream = null;
            requests.remove(state.context.sessionId(), handle);
        }
    }

    private boolean processSseData(SessionState state, StringBuilder data) {
        if (data.isEmpty() || "[DONE]".equals(data.toString())) {
            return false;
        }
        final JsonNode event;
        try {
            event = objectMapper.readTree(data.toString());
        } catch (IOException error) {
            appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
            return true;
        }
        String status = event.path("status").asText();
        if ("failed".equals(status)) {
            appendFailure(state, ConversationRuntimeException.Code.MODEL_PROVIDER_UNAVAILABLE);
            return true;
        }
        if ("cancelled".equals(status)) {
            appendFailure(state, ConversationRuntimeException.Code.CANCELLED);
            return true;
        }
        if ("completed".equals(status)) {
            appendIfNotCancelled(state, "message.completed", data.toString());
            return true;
        }
        if ("message".equals(event.path("type").asText())
                || event.path("delta").asBoolean(false)
                || "in_progress".equals(status)) {
            appendIfNotCancelled(state, "message.delta", data.toString());
        }
        return false;
    }

    private void publishFailure(SessionState state, RequestHandle handle,
            ConversationRuntimeException.Code code) {
        if (handle.cancelled()) {
            return;
        }
        appendFailure(state, code);
        requests.remove(state.context.sessionId(), handle);
    }

    private void appendFailure(SessionState state, ConversationRuntimeException.Code code) {
        appendIfNotCancelled(state, "conversation.failed",
                "{\"code\":\"" + code.name() + "\"}");
    }

    private void appendIfNotCancelled(SessionState state, String type, String data) {
        synchronized (state) {
            if (!state.cancelled) {
                append(state, type, data);
            }
        }
    }

    private void append(SessionState state, String type, String data) {
        state.events.add(ConversationEvent.of(state.context.sessionId(), state.events.size() + 1,
                type, data, Instant.now()));
    }

    private String requestBody(Context context, Message message) throws RuntimeException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode input = body.putArray("input");
        ObjectNode userMessage = input.addObject();
        userMessage.put("role", "user");
        userMessage.putArray("content").addObject().put("type", "text").put("text", message.content());
        body.put("session_id", context.sessionId().toString());
        body.put("user_id", configuration.userId());
        body.put("channel", configuration.channel());
        body.put("project", context.project());
        body.put("team", context.team());
        if (context.worker() != null) {
            body.put("worker", context.worker());
        }
        if (context.task() != null) {
            body.put("task", context.task());
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException error) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "unable to encode QwenPaw request", error);
        }
    }

    private void readLimited(InputStream stream) throws IOException, ResponseTooLargeException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > configuration.maxResponseBytes()) {
                throw new ResponseTooLargeException();
            }
        }
    }

    private SessionState session(UUID sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw ConversationRuntimeException.sessionNotFound();
        }
        return state;
    }

    private URI chatEndpoint() {
        String base = configuration.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + "/api/console/chat");
    }

    private static ConversationRuntimeException.Code classifyHttp(int status) {
        if (status == 401 || status == 403 || status == 429) {
            return ConversationRuntimeException.Code.MODEL_PROVIDER_UNAVAILABLE;
        }
        if (status == 502 || status == 503 || status == 504) {
            return ConversationRuntimeException.Code.WORKER_UNAVAILABLE;
        }
        return ConversationRuntimeException.Code.HTTP_FAILURE;
    }

    private static ConversationRuntimeException.Code classifyTransport(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof java.net.http.HttpTimeoutException
                ? ConversationRuntimeException.Code.TIMEOUT
                : ConversationRuntimeException.Code.CONNECTION_CLOSED;
    }

    private static final class SessionState {
        private final Context context;
        private final List<ConversationEvent> events = new ArrayList<>();
        private boolean started;
        private boolean cancelled;

        private SessionState(Context context) {
            this.context = context;
        }
    }

    private static final class RequestHandle {
        private final UUID sessionId;
        private volatile CompletableFuture<?> future;
        private volatile InputStream stream;
        private volatile boolean cancelled;

        private RequestHandle(UUID sessionId) {
            this.sessionId = sessionId;
        }

        private boolean cancelled() {
            return cancelled;
        }

        private void cancel() {
            cancelled = true;
            CompletableFuture<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            InputStream currentStream = stream;
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (IOException ignored) {
                    // Local cancellation is best effort.
                }
            }
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
