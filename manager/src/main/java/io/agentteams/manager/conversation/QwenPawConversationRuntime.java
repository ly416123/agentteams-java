package io.agentteams.manager.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conversation runtime adapter for QwenPaw's HTTP/SSE console API. */
public final class QwenPawConversationRuntime implements ConversationRuntimePort, AutoCloseable {
    private static final int MAX_SSE_LINE_BYTES = 64 * 1024;
    private final ConversationRuntimeConfiguration configuration;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, RequestHandle> requests = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final Semaphore requestSlots;
    private final Semaphore sessionSlots;
    private final AtomicBoolean closed = new AtomicBoolean();

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
        this.requestSlots = new Semaphore(configuration.maxConcurrentRequests());
        this.sessionSlots = new Semaphore(configuration.maxSessions());
        this.readerExecutor = Executors.newFixedThreadPool(configuration.maxConcurrentRequests(), runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-conversation-sse-reader");
            thread.setDaemon(true);
            return thread;
        });
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-conversation-sse-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(Context context) {
        Objects.requireNonNull(context, "context");
        ensureOpen();
        SessionState candidate = new SessionState(context);
        SessionState state = sessions.get(context.sessionId());
        if (state == null) {
            if (!sessionSlots.tryAcquire()) {
                throw resourceExhausted("conversation session limit reached");
            }
            state = sessions.putIfAbsent(context.sessionId(), candidate);
            if (state == null) {
                state = candidate;
            } else {
                sessionSlots.release();
            }
        }
        if (!state.context.equals(context)) {
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
        ensureOpen();
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
            if (state.request != null) {
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                        "conversation already has a request in flight");
            }
            if (!requestSlots.tryAcquire()) {
                throw resourceExhausted("conversation request limit reached");
            }
            RequestHandle handle = new RequestHandle(message.sessionId(), requestSlots);
            state.request = handle;
            requests.put(message.sessionId(), handle);
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(chatEndpoint())
                        .timeout(configuration.requestTimeout())
                        .header("Accept", "text/event-stream")
                        .header("Content-Type", "application/json")
                        .header("X-Agent-Id", configuration.agentId())
                        .header("Idempotency-Key", message.idempotencyKey());
                if (configuration.authorizationToken() != null) {
                    builder.header("Authorization", "Bearer " + configuration.authorizationToken());
                }
                HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                        requestBody(state.context, message), StandardCharsets.UTF_8)).build();
                CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                handle.upstreamFuture = future;
                CompletableFuture<Void> processing = future.thenAcceptAsync(
                        response -> processResponse(state, handle, response), readerExecutor);
                handle.future = processing;
                processing
                        .whenComplete((ignored, error) -> {
                            if (error != null && !handle.cancelled()) {
                                publishFailure(state, handle, classifyTransport(error));
                            }
                        });
            } catch (RuntimeException error) {
                clearRequest(state, handle);
                handle.cancel();
                throw error;
            }
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
        ensureOpen();
        SessionState state = session(sessionId);
        RequestHandle handle;
        synchronized (state) {
            if (state.cancelled && state.remoteCancelSucceeded) {
                return;
            }
            state.cancelRequested = true;
            handle = state.request;
        }
        ConversationRuntimeException remoteFailure = null;
        try {
            cancelRemote(state.context);
            synchronized (state) {
                state.cancelled = true;
                state.cancelRequested = false;
                state.remoteCancelSucceeded = true;
                append(state, "conversation.cancelled", "{}");
            }
        } catch (ConversationRuntimeException error) {
            synchronized (state) {
                state.cancelRequested = false;
            }
            remoteFailure = error;
        } finally {
            if (handle != null) handle.cancel();
        }
        if (remoteFailure != null) {
            throw remoteFailure;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (SessionState state : sessions.values()) {
            synchronized (state) {
                if (state.request != null) {
                    state.request.cancel();
                }
            }
        }
        requests.values().forEach(handle -> {
            handle.cancel();
            handle.releaseSlot();
        });
        requests.clear();
        readerExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
    }

    private void processResponse(SessionState state, RequestHandle handle,
            HttpResponse<InputStream> response) {
        if (handle.cancelled()) {
            try {
                response.body().close();
            } catch (IOException ignored) {
                // Cancellation cleanup is best effort.
            }
            clearRequest(state, handle);
            return;
        }
        handle.stream = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            ConversationRuntimeException.Code code = classifyHttp(response.statusCode());
            handle.armIdleTimeout(timeoutExecutor, configuration.requestTimeout());
            try (InputStream body = response.body()) {
                readLimited(handle, body);
            } catch (IOException error) {
                // Preserve the HTTP status category even when its body is incomplete.
            } finally {
                handle.cancelIdleTimeout();
                handle.stream = null;
                publishFailure(state, handle, code);
                clearRequest(state, handle);
            }
            return;
        }

        handle.armIdleTimeout(timeoutExecutor, configuration.requestTimeout());
        if (!isSseContentType(response)) {
            try (InputStream body = response.body()) {
                readLimited(handle, body);
            } catch (IOException ignored) {
                // The response is already classified as a protocol error.
            } finally {
                handle.cancelIdleTimeout();
                handle.stream = null;
                publishFailure(state, handle, ConversationRuntimeException.Code.PROTOCOL_ERROR);
                clearRequest(state, handle);
            }
            return;
        }

        boolean terminal = false;
        try (InputStream body = response.body()) {
            terminal = readSse(state, handle, body);
            if (!terminal && !handle.cancelled()) {
                publishFailure(state, handle, handle.timedOut()
                        ? ConversationRuntimeException.Code.TIMEOUT
                        : ConversationRuntimeException.Code.CONNECTION_CLOSED);
            }
        } catch (ResponseTooLargeException error) {
            publishFailure(state, handle, ConversationRuntimeException.Code.PROTOCOL_ERROR);
        } catch (IOException error) {
            if (!handle.cancelled()) {
                publishFailure(state, handle, handle.timedOut()
                        ? ConversationRuntimeException.Code.TIMEOUT
                        : ConversationRuntimeException.Code.CONNECTION_CLOSED);
            }
        } catch (ConversationRuntimeException error) {
            publishFailure(state, handle, error.code());
        } finally {
            handle.cancelIdleTimeout();
            handle.stream = null;
            clearRequest(state, handle);
        }
    }

    private boolean readSse(SessionState state, RequestHandle handle, InputStream body)
            throws IOException, ResponseTooLargeException {
        StringBuilder data = new StringBuilder();
        StringBuilder eventType = new StringBuilder();
        StringBuilder sourceEventId = new StringBuilder();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalBytes = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            handle.touch(timeoutExecutor, configuration.requestTimeout());
            totalBytes += read;
            if (totalBytes > configuration.maxResponseBytes()) {
                throw new ResponseTooLargeException();
            }
            for (int index = 0; index < read; index++) {
                byte value = buffer[index];
                if (value == '\n') {
                    if (processSseLine(state, data, eventType, sourceEventId, line)) {
                        return true;
                    }
                    line.reset();
                } else {
                    line.write(value);
                    if (line.size() > MAX_SSE_LINE_BYTES) {
                        throw new ResponseTooLargeException();
                    }
                }
            }
        }
        if (line.size() > 0) {
            processSseLine(state, data, eventType, sourceEventId, line);
        }
        return !data.isEmpty() && processSseData(state, data, eventType.toString(), sourceEventId.toString());
    }

    private boolean processSseLine(SessionState state, StringBuilder data,
            StringBuilder eventType, StringBuilder sourceEventId, ByteArrayOutputStream line) {
        String value = line.toString(StandardCharsets.UTF_8);
        if (value.endsWith("\r")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            boolean terminal = processSseData(state, data, eventType.toString(), sourceEventId.toString());
            data.setLength(0);
            eventType.setLength(0);
            sourceEventId.setLength(0);
            return terminal;
        }
        if (value.startsWith("data:")) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(value.substring("data:".length()).trim());
        } else if (value.startsWith("event:")) {
            eventType.setLength(0);
            eventType.append(value.substring("event:".length()).trim());
        } else if (value.startsWith("id:")) {
            sourceEventId.setLength(0);
            sourceEventId.append(value.substring("id:".length()).trim());
        }
        return false;
    }

    private boolean processSseData(SessionState state, StringBuilder data, String eventType, String sourceEventId) {
        sourceEventId = sourceEventId == null || sourceEventId.isBlank() ? null : sourceEventId;
        if (data.isEmpty()) {
            if (!eventType.isEmpty() && !isSupportedEventType(eventType)) {
                appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
                return true;
            }
            return false;
        }
        if (!eventType.isEmpty() && !isSupportedEventType(eventType)) {
            appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
            return true;
        }
        if ("[DONE]".equals(data.toString())) {
            return true;
        }
        final JsonNode event;
        try {
            event = objectMapper.readTree(data.toString());
        } catch (IOException error) {
            appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
            return true;
        }
        if (event == null || !event.isObject()) {
            appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
            return true;
        }
        String status = event.path("status").asText();
        if (!eventType.isEmpty() && switch (eventType) {
            case "task.created", "task.updated", "tool.started", "tool.completed" -> true;
            default -> false;
        }) {
            appendIfNotCancelled(state, eventType, data.toString(), sourceEventId);
            // Keep the explicit legacy tool event contract: callers may use
            // tool.completed as the terminal marker for custom runtimes. The
            // official QwenPaw plugin path is represented as message.completed
            // with type=data and is handled below as an intermediate event.
            return "tool.completed".equals(eventType);
        }
        if ("failed".equals(status)) {
            appendFailure(state, ConversationRuntimeException.Code.MODEL_PROVIDER_UNAVAILABLE, sourceEventId);
            return true;
        }
        if ("cancelled".equals(status)) {
            appendFailure(state, ConversationRuntimeException.Code.CANCELLED, sourceEventId);
            return true;
        }
        String eventTypeValue = event.path("type").asText();
        if ("text".equals(eventTypeValue) && event.has("delta")) {
            if (event.path("delta").asBoolean(false)) {
                appendIfNotCancelled(state, "message.delta", data.toString(), sourceEventId);
            }
            // QwenPaw emits a delta=false content snapshot after the streamed
            // chunks. The snapshot is not a terminal response and must not be
            // surfaced as another delta or treated as an unknown protocol event.
            return false;
        }
        if ("completed".equals(status)) {
            String object = event.path("object").asText();
            if ("message".equals(object) || "message".equals(eventTypeValue)
                    || "reasoning".equals(eventTypeValue)) {
                // QwenPaw completes reasoning and assistant message objects
                // before it completes the enclosing response.
                return false;
            }
            if (!"response".equals(object) && ("data".equals(eventTypeValue) || event.has("name")
                    || event.has("tool"))) {
                // Tool/plugin messages can be reported as message.completed by
                // QwenPaw, but they do not complete the enclosing response.
                appendIfNotCancelled(state, "message.delta", data.toString(), sourceEventId);
                return false;
            }
            appendIfNotCancelled(state, "message.completed", data.toString(), sourceEventId);
            return true;
        }
        if (!eventType.isEmpty() && "conversation.started".equals(eventType)
                && "created".equals(status)) {
            return false;
        }
        if ("message".equals(event.path("type").asText())
                || event.path("delta").asBoolean(false)
                || (!eventType.isEmpty() && "message.delta".equals(eventType))
                || (eventType.isEmpty() && "in_progress".equals(status))) {
            appendIfNotCancelled(state, "message.delta", data.toString(), sourceEventId);
            return false;
        }
        if ("created".equals(status)) {
            return false;
        }
        appendFailure(state, ConversationRuntimeException.Code.PROTOCOL_ERROR);
        return true;
    }

    private void publishFailure(SessionState state, RequestHandle handle,
            ConversationRuntimeException.Code code) {
        if (handle.cancelled() || state.cancelRequested) {
            return;
        }
        appendFailure(state, code);
        clearRequest(state, handle);
    }

    private void appendFailure(SessionState state, ConversationRuntimeException.Code code) {
        appendFailure(state, code, null);
    }

    private void appendFailure(SessionState state, ConversationRuntimeException.Code code, String sourceEventId) {
        appendIfNotCancelled(state, "conversation.failed",
                "{\"code\":\"" + code.name() + "\"}", sourceEventId);
    }

    private static boolean isSupportedEventType(String eventType) {
        return switch (eventType) {
            case "conversation.started", "message.delta", "message.completed", "task.created", "task.updated",
                    "tool.started", "tool.completed", "conversation.cancelled", "conversation.failed" -> true;
            default -> false;
        };
    }

    private static boolean isTerminalEvent(String type) {
        return "message.completed".equals(type)
                || "conversation.failed".equals(type)
                || "conversation.cancelled".equals(type);
    }

    private static boolean isSseContentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.split(";", 2)[0].trim())
                .map("text/event-stream"::equalsIgnoreCase)
                .orElse(false);
    }

    private void appendIfNotCancelled(SessionState state, String type, String data) {
        appendIfNotCancelled(state, type, data, null);
    }

    private void appendIfNotCancelled(SessionState state, String type, String data, String sourceEventId) {
        synchronized (state) {
            if (!state.cancelled) {
                append(state, type, data, sourceEventId);
            }
        }
    }

    private void append(SessionState state, String type, String data) {
        append(state, type, data, null);
    }

    private void append(SessionState state, String type, String data, String sourceEventId) {
        if (state.events.size() > configuration.maxEventsPerSession()
                || (state.events.size() >= configuration.maxEventsPerSession()
                        && !isTerminalEvent(type))) {
            throw resourceExhausted("conversation event history limit reached");
        }
        state.events.add(ConversationEvent.of(state.context.sessionId(), state.events.size() + 1,
                type, data, Instant.now(), sourceEventId));
    }

    private void clearRequest(SessionState state, RequestHandle handle) {
        synchronized (state) {
            if (state.request == handle) {
                state.request = null;
            }
        }
        requests.remove(state.context.sessionId(), handle);
        handle.releaseSlot();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.INVALID_STATE,
                    "conversation runtime is closed");
        }
    }

    private static ConversationRuntimeException resourceExhausted(String message) {
        return new ConversationRuntimeException(ConversationRuntimeException.Code.RESOURCE_EXHAUSTED,
                message);
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

    private void readLimited(RequestHandle handle, InputStream stream)
            throws IOException, ResponseTooLargeException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            handle.touch(timeoutExecutor, configuration.requestTimeout());
            total += read;
            if (total > configuration.maxResponseBytes()) {
                throw new ResponseTooLargeException();
            }
        }
    }

    private void cancelRemote(Context context) {
        RequestHandle cancelHandle = new RequestHandle(context.sessionId(), null);
        int status = cancelRemoteOnce(context, cancelEndpoint(context), cancelHandle);
        if (status == 404 || status == 405) {
            // Older/local QwenPaw-compatible runtimes and the deterministic
            // mock expose the pre-2.x endpoint. Keep that compatibility path
            // only for an explicit route-not-found/method-not-allowed result.
            status = cancelRemoteOnce(context, legacyCancelEndpoint(), cancelHandle);
        }
        if (status < 200 || status >= 300) {
            throw new ConversationRuntimeException(classifyHttp(status),
                    "QwenPaw cancellation failed with HTTP status " + status);
        }
    }

    private int cancelRemoteOnce(Context context, URI endpoint, RequestHandle cancelHandle) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(configuration.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Agent-Id", configuration.agentId());
        if (configuration.authorizationToken() != null) {
            builder.header("Authorization", "Bearer " + configuration.authorizationToken());
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(builder.POST(
                    HttpRequest.BodyPublishers.ofString(cancelBody(context), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            ConversationRuntimeException.Code statusCode = classifyHttp(status);
            cancelHandle.stream = response.body();
            if (status == 404 || status == 405) {
                // Route probing is intentional for compatibility with older
                // QwenPaw-compatible runtimes. Do not wait for an error body
                // from a server that may leave a route-miss response open.
                response.body().close();
                cancelHandle.stream = null;
                return status;
            }
            cancelHandle.armIdleTimeout(timeoutExecutor, configuration.requestTimeout());
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if ((status < 200 || status >= 300)
                    && declaredLength > configuration.maxResponseBytes()) {
                cancelHandle.cancelIdleTimeout();
                cancelHandle.stream = null;
                throw new ConversationRuntimeException(statusCode,
                        "QwenPaw cancellation failed with HTTP status " + status);
            }
            try (InputStream body = response.body()) {
                readLimited(cancelHandle, body);
            } catch (ResponseTooLargeException error) {
                if (status < 200 || status >= 300) {
                    throw new ConversationRuntimeException(statusCode,
                            "QwenPaw cancellation failed with HTTP status " + status, error);
                }
                throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                        "QwenPaw cancellation response was too large", error);
            } catch (IOException error) {
                if (cancelHandle.timedOut()) {
                    throw new ConversationRuntimeException(ConversationRuntimeException.Code.TIMEOUT,
                            "QwenPaw cancellation response timed out", error);
                }
                if (status < 200 || status >= 300) {
                    throw new ConversationRuntimeException(statusCode,
                            "QwenPaw cancellation failed with HTTP status " + status, error);
                }
                throw new ConversationRuntimeException(cancelHandle.timedOut()
                        ? ConversationRuntimeException.Code.TIMEOUT : classifyTransport(error),
                        "QwenPaw cancellation response could not be read", error);
            } finally {
                cancelHandle.cancelIdleTimeout();
                cancelHandle.stream = null;
            }
            return status;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.TIMEOUT,
                    "QwenPaw cancellation was interrupted", interrupted);
        } catch (ResponseTooLargeException error) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "QwenPaw cancellation response was too large", error);
        } catch (IOException error) {
            throw new ConversationRuntimeException(classifyTransport(error),
                    "QwenPaw cancellation request failed", error);
        }
    }

    private String cancelBody(Context context) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("session_id", context.sessionId().toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException error) {
            throw new ConversationRuntimeException(ConversationRuntimeException.Code.PROTOCOL_ERROR,
                    "unable to encode QwenPaw cancellation request", error);
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

    private URI cancelEndpoint(Context context) {
        String base = configuration.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + "/api/console/chat/stop?chat_id="
                + context.sessionId());
    }

    private URI legacyCancelEndpoint() {
        String base = configuration.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + "/api/console/cancel");
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

    static ConversationRuntimeException.Code classifyTransport(Throwable error) {
        Throwable current = error;
        boolean timeout = false;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnresolvedAddressException
                    || current instanceof UnknownHostException
                    || current instanceof HttpConnectTimeoutException) {
                return ConversationRuntimeException.Code.WORKER_UNAVAILABLE;
            }
            timeout |= current instanceof java.net.http.HttpTimeoutException;
            current = current.getCause();
        }
        return timeout
                ? ConversationRuntimeException.Code.TIMEOUT
                : ConversationRuntimeException.Code.CONNECTION_CLOSED;
    }

    private static final class SessionState {
        private final Context context;
        private final List<ConversationEvent> events = new ArrayList<>();
        private RequestHandle request;
        private boolean started;
        private boolean cancelled;
        private boolean cancelRequested;
        private boolean remoteCancelSucceeded;

        private SessionState(Context context) {
            this.context = context;
        }
    }

    private static final class RequestHandle {
        private final UUID sessionId;
        private final Semaphore slots;
        private volatile CompletableFuture<?> future;
        private volatile CompletableFuture<?> upstreamFuture;
        private volatile InputStream stream;
        private volatile boolean cancelled;
        private volatile boolean timedOut;
        private volatile ScheduledFuture<?> idleTimeout;
        private final AtomicBoolean slotReleased = new AtomicBoolean();

        private RequestHandle(UUID sessionId, Semaphore slots) {
            this.sessionId = sessionId;
            this.slots = slots;
        }

        private boolean cancelled() {
            return cancelled;
        }

        private boolean timedOut() {
            return timedOut;
        }

        private void armIdleTimeout(ScheduledExecutorService scheduler, java.time.Duration timeout) {
            cancelIdleTimeout();
            idleTimeout = scheduler.schedule(() -> {
                timedOut = true;
                InputStream currentStream = stream;
                if (currentStream != null) {
                    try {
                        currentStream.close();
                    } catch (IOException ignored) {
                        // Timeout cleanup is best effort.
                    }
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void touch(ScheduledExecutorService scheduler, java.time.Duration timeout) {
            if (!cancelled && !timedOut) {
                armIdleTimeout(scheduler, timeout);
            }
        }

        private void cancelIdleTimeout() {
            ScheduledFuture<?> currentTimeout = idleTimeout;
            if (currentTimeout != null) {
                currentTimeout.cancel(false);
                idleTimeout = null;
            }
        }

        private void releaseSlot() {
            if (slotReleased.compareAndSet(false, true)) {
                if (slots != null) {
                    slots.release();
                }
            }
        }

        private void cancel() {
            cancelled = true;
            cancelIdleTimeout();
            CompletableFuture<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            CompletableFuture<?> currentUpstream = upstreamFuture;
            if (currentUpstream != null) {
                currentUpstream.cancel(true);
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
