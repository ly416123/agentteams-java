package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QwenPawConversationRuntimeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final ConversationRuntimePort.Context CONTEXT = new ConversationRuntimePort.Context(
            "project-a", "team-a", "worker-a", "task-a", SESSION_ID);

    private HttpServer server;
    private ExecutorService serverExecutor;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> agentId;
    private AtomicReference<String> authorization;
    private AtomicReference<String> idempotencyKey;
    private AtomicReference<String> cancelBody;
    private AtomicReference<String> cancelQuery;
    private AtomicInteger redirectedRequests;
    private AtomicInteger cancelRequests;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        agentId = new AtomicReference<>();
        authorization = new AtomicReference<>();
        idempotencyKey = new AtomicReference<>();
        cancelBody = new AtomicReference<>();
        cancelQuery = new AtomicReference<>();
        redirectedRequests = new AtomicInteger();
        cancelRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        // Existing tests use a prefix handler for /api/console/chat. Register
        // the more specific official stop route so that route probing reaches
        // the legacy fallback without entering a deliberately long-lived chat
        // handler.
        server.createContext("/api/console/chat/stop", exchange ->
                writeResponse(exchange, 404, "application/json", "{\"detail\":\"not found\"}"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void sendsOfficialRequestAndPublishesDeltaAndCompletedEvents() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            writeResponse(exchange, 200, "text/event-stream",
                    "data: {\"status\":\"created\"}\n\n"
                            + "data: {\"type\":\"message\",\"role\":\"assistant\","
                            + "\"content\":[{\"text\":\"hel\"}],\"status\":\"in_progress\"}\n\n"
                            + "data: {\"id\":\"response-1\",\"status\":\"completed\","
                            + "\"object\":\"response\",\"output\":[{\"type\":\"message\","
                            + "\"role\":\"assistant\",\"content\":[{\"text\":\"hello\"}]}]}\n\n");
        });
        server.start();

        QwenPawConversationRuntime runtime = runtime("secret", 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "private prompt"));

        awaitEvents(runtime, 3);
        List<ConversationEvent> events = runtime.events(SESSION_ID, 0);
        assertThat(events).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "message.completed");
        assertThat(events).extracting(ConversationEvent::cursor).containsExactly(1L, 2L, 3L);
        assertThat(events.get(2).data()).contains("hello");
        assertThat(agentId.get()).isEqualTo("agent-a");
        assertThat(authorization.get()).isEqualTo("Bearer secret");
        assertThat(idempotencyKey.get()).isEqualTo("message-1");
        JsonNode request = MAPPER.readTree(requestBody.get());
        assertThat(request.path("session_id").asText()).isEqualTo(SESSION_ID.toString());
        assertThat(request.path("input").get(0).path("content").get(0).path("text").asText())
                .isEqualTo("private prompt");
        assertThat(request.path("project").asText()).isEqualTo("project-a");
        runtime.close();
    }

    @Test
    void acceptsQwenPawContentSnapshotsAndIntermediateMessageCompletion() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            writeResponse(exchange, 200, "text/event-stream",
                    "data: {\"id\":\"response-1\",\"status\":\"created\",\"object\":\"response\"}\n\n"
                            + "data: {\"id\":\"response-1\",\"status\":\"in_progress\",\"object\":\"response\"}\n\n"
                            + "data: {\"id\":\"reasoning-1\",\"type\":\"reasoning\",\"object\":\"message\",\"status\":\"in_progress\"}\n\n"
                            + "data: {\"type\":\"text\",\"delta\":true,\"object\":\"content\",\"text\":\"thinking\"}\n\n"
                            + "data: {\"type\":\"text\",\"delta\":false,\"object\":\"content\",\"text\":\"thinking\"}\n\n"
                            + "data: {\"id\":\"reasoning-1\",\"type\":\"reasoning\",\"object\":\"message\",\"status\":\"completed\"}\n\n"
                            + "data: {\"id\":\"message-1\",\"type\":\"message\",\"object\":\"message\",\"status\":\"in_progress\"}\n\n"
                            + "data: {\"type\":\"text\",\"delta\":true,\"object\":\"content\",\"text\":\"hello\"}\n\n"
                            + "data: {\"type\":\"text\",\"delta\":false,\"object\":\"content\",\"text\":\"hello\"}\n\n"
                            + "data: {\"id\":\"message-1\",\"type\":\"message\",\"object\":\"message\",\"status\":\"completed\"}\n\n"
                            + "data: {\"id\":\"response-1\",\"status\":\"completed\",\"object\":\"response\",\"output\":[]}\n\n");
        });
        server.start();

        QwenPawConversationRuntime runtime = runtime("secret", 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 6);
        List<ConversationEvent> events = runtime.events(SESSION_ID, 0);
        assertThat(events).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "message.delta",
                        "message.delta", "message.delta", "message.delta", "message.completed");
        assertThat(events).noneMatch(event -> event.type().equals("conversation.failed"));
        runtime.close();
    }

    @Test
    void classifiesHttp503WithoutPublishingTheRemoteBody() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 503,
                "application/json", "{\"message\":\"secret provider detail\"}"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        ConversationEvent failure = runtime.events(SESSION_ID, 1).get(0);
        assertThat(failure.type()).isEqualTo("conversation.failed");
        assertThat(failure.data()).contains("WORKER_UNAVAILABLE").doesNotContain("secret provider detail");
        assertThat(authorization.get()).isNull();
        runtime.close();
    }

    @Test
    void classifiesHttpFailureEvenWhenErrorBodyIsTooLarge() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 503,
                "application/json", "0123456789"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 4);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("WORKER_UNAVAILABLE");
        runtime.close();
    }

    @Test
    void cancellationResponseKeepsHttpStatusClassificationWhenBodyEndsUnexpectedly() throws Exception {
        server.createContext("/api/console/chat", exchange ->
                writeResponse(exchange, 200, "text/event-stream", "data: {\"status\":\"in_progress\"}\n\n"));
        server.createContext("/api/console/cancel", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Content-Length", "999999");
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        awaitEvents(runtime, 2);

        assertThatThrownBy(() -> runtime.cancel(SESSION_ID))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE));
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .contains("conversation.started", "message.delta");
        runtime.close();
    }

    @Test
    void cancellationResponseKeepsTimeoutWhenItsBodyStaysIdle() throws Exception {
        CountDownLatch cancelResponseReady = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange ->
                writeResponse(exchange, 200, "text/event-stream", "data: {\"status\":\"in_progress\"}\n\n"));
        server.createContext("/api/console/cancel", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, 0);
            cancelResponseReady.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort()), null, 8192, Duration.ofMillis(100));
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        assertThatThrownBy(() -> runtime.cancel(SESSION_ID))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.TIMEOUT));
        assertThat(cancelResponseReady.await(5, TimeUnit.SECONDS)).isTrue();
        runtime.close();
    }

    @Test
    void timesOutWhenNonSuccessResponseBodyStaysIdle() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, 0);
            responseReady.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort()), null, 8192, Duration.ofMillis(100));
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("WORKER_UNAVAILABLE");
        runtime.close();
    }

    @Test
    void classifiesConnectionRefusedAsWorkerUnavailable() throws Exception {
        server.start();
        int unusedPort = server.getAddress().getPort();
        server.stop(0);
        QwenPawConversationRuntime runtime = runtime(URI.create("http://127.0.0.1:" + unusedPort),
                null, 8192, Duration.ofSeconds(2));
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("WORKER_UNAVAILABLE");
        runtime.close();
    }

    @Test
    void classifiesUnresolvedAddressAndDnsFailureAsWorkerUnavailable() {
        assertThat(QwenPawConversationRuntime.classifyTransport(
                new CompletionException(new java.nio.channels.UnresolvedAddressException())))
                .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE);
        assertThat(QwenPawConversationRuntime.classifyTransport(
                new CompletionException(new java.net.UnknownHostException("worker.invalid"))))
                .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE);
    }

    @Test
    void classifiesFailedSseAsModelProviderUnavailableWithoutRemoteDetail() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "data: {\"status\":\"failed\","
                        + "\"error\":{\"message\":\"secret provider detail\"}}\n\n"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        ConversationEvent failure = runtime.events(SESSION_ID, 1).get(0);
        assertThat(failure.type()).isEqualTo("conversation.failed");
        assertThat(failure.data()).contains("MODEL_PROVIDER_UNAVAILABLE")
                .doesNotContain("secret provider detail");
        runtime.close();
    }

    @Test
    void rejectsNonSseContentTypeAsProtocolError() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "application/json", "{\"status\":\"completed\"}"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("PROTOCOL_ERROR");
        runtime.close();
    }

    @Test
    void rejectsUnknownSseEventAndAcceptsDoneAsTerminal() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "event: unknown\ndata: {}\n\n"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("PROTOCOL_ERROR");
        runtime.close();

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "data: [DONE]\n\n"));
        server.start();
        QwenPawConversationRuntime doneRuntime = runtime(null, 8192);
        doneRuntime.start(CONTEXT);
        doneRuntime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-2", "hello"));
        Thread.sleep(100);
        assertThat(doneRuntime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started");
        doneRuntime.close();
    }

    @Test
    void mapsDeclaredSseEventsByEventNameAndPreservesTheirPayloads() throws Exception {
        String taskCreated = "{\"status\":\"created\",\"task_id\":\"task-1\"}";
        String taskUpdated = "{\"status\":\"in_progress\",\"task_id\":\"task-1\",\"step\":2}";
        String toolStarted = "{\"status\":\"in_progress\",\"tool\":\"search\",\"call_id\":\"call-1\"}";
        String toolCompleted = "{\"status\":\"completed\",\"tool\":\"search\",\"result\":\"ok\"}";
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "event: task.created\ndata: " + taskCreated + "\n\n"
                        + "event: task.updated\ndata: " + taskUpdated + "\n\n"
                        + "event: tool.started\ndata: " + toolStarted + "\n\n"
                        + "event: tool.completed\ndata: " + toolCompleted + "\n\n"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 5);
        List<ConversationEvent> events = runtime.events(SESSION_ID, 0);
        assertThat(events).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "task.created", "task.updated",
                        "tool.started", "tool.completed");
        assertThat(events).extracting(ConversationEvent::data)
                .containsExactly("{}", taskCreated, taskUpdated, toolStarted, toolCompleted);
        runtime.close();
    }

    @Test
    void rejectsUnknownSseEventWithoutDataOrWithDoneMarker() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "event: unknown\n\n"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("PROTOCOL_ERROR");
        runtime.close();

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "event: unknown\ndata: [DONE]\n\n"));
        server.start();
        QwenPawConversationRuntime doneRuntime = runtime(null, 8192);
        doneRuntime.start(CONTEXT);
        doneRuntime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-2", "hello"));
        awaitEvents(doneRuntime, 2);
        assertThat(doneRuntime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.failed");
        doneRuntime.close();
    }

    @Test
    void timesOutWhenResponseHeadersArriveButSseBodyStaysIdle() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseReady.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/console/cancel", exchange ->
                writeResponse(exchange, 200, "application/json", "{\"status\":\"cancelled\"}"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                null, 8192, Duration.ofMillis(100));
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("TIMEOUT");
        runtime.close();
    }

    @Test
    void rejectsAnOverlongSseLineEvenWhenTotalResponseLimitAllowsIt() throws Exception {
        String longLine = "data: {\"status\":\"in_progress\",\"text\":\""
                + "a".repeat(70_000) + "\"}\n\n";
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", longLine));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 1_000_000);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("PROTOCOL_ERROR");
        runtime.close();
    }

    @Test
    void rejectsOversizedResponseAndDoesNotFollowRedirects() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            writeResponse(exchange, 302, "text/plain", "redirect");
        });
        server.createContext("/redirect-target", exchange -> {
            redirectedRequests.incrementAndGet();
            writeResponse(exchange, 200, "text/event-stream", "data: {}\n\n");
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 4);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 2);
        assertThat(runtime.events(SESSION_ID, 1).get(0).data()).contains("HTTP_FAILURE");
        assertThat(redirectedRequests).hasValue(0);
        runtime.close();
    }

    @Test
    void localCancellationSuppressesLateRemoteCompletion() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseReady.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(
                        "data: {\"status\":\"completed\",\"object\":\"response\"}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/console/cancel", exchange ->
                writeResponse(exchange, 200, "application/json", "{\"status\":\"cancelled\"}"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.cancel(SESSION_ID);
        releaseResponse.countDown();
        Thread.sleep(200);

        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.cancelled");
        runtime.close();
    }

    @Test
    void cancellationCallsRemoteEndpointBeforeClosingLocalStream() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseReady.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write(
                        "data: {\"status\":\"completed\"}\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/console/cancel", exchange -> {
            cancelRequests.incrementAndGet();
            cancelBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeResponse(exchange, 200, "application/json", "{\"status\":\"cancelled\"}");
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        runtime.cancel(SESSION_ID);
        releaseResponse.countDown();
        Thread.sleep(100);

        assertThat(cancelRequests).hasValue(1);
        assertThat(cancelBody.get()).contains(SESSION_ID.toString());
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.cancelled");
        runtime.close();
    }

    @Test
    void usesOfficialQwenPawChatStopEndpointWithSessionQuery() throws Exception {
        server.removeContext("/api/console/chat/stop");
        server.createContext("/api/console/chat/stop", exchange -> {
            cancelQuery.set(exchange.getRequestURI().getQuery());
            writeResponse(exchange, 200, "application/json", "{\"stopped\":true}");
        });
        server.start();

        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.cancel(SESSION_ID);

        assertThat(cancelQuery).hasValue("chat_id=" + SESSION_ID);
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "conversation.cancelled");
        runtime.close();
    }

    @Test
    void cancellationFailureLeavesRuntimeActiveAndAllowsRetry() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            responseReady.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/api/console/cancel", exchange ->
                writeResponse(exchange, 503, "application/json", "provider unavailable"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> runtime.cancel(SESSION_ID))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.WORKER_UNAVAILABLE));
        releaseResponse.countDown();
        Thread.sleep(100);
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started");
        runtime.close();
    }

    @Test
    void cancellationWinsBeforeAConcurrentSendCanRegisterARequest() throws Exception {
        server.createContext("/api/console/cancel", exchange ->
                writeResponse(exchange, 200, "application/json", "{\"status\":\"cancelled\"}"));
        server.createContext("/api/console/chat", exchange -> {
            throw new AssertionError("chat must not be called after cancellation");
        });
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancellation = executor.submit(() -> runtime.cancel(SESSION_ID));
            cancellation.get(5, TimeUnit.SECONDS);
            Future<?> send = executor.submit(() -> runtime.send(
                    new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello")));
            assertThatThrownBy(() -> send.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConversationRuntimeException.class);
        } finally {
            executor.shutdownNow();
            runtime.close();
        }
    }

    @Test
    void enforcesConfiguredConcurrentRequestLimitWithoutDroppingEvents() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/api/console/chat", exchange -> {
            responseReady.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "agent-a", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 8192, "agentteams", "console", 1, 10, 10);
        QwenPawConversationRuntime runtime = new QwenPawConversationRuntime(configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), MAPPER);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        assertThat(responseReady.await(5, TimeUnit.SECONDS)).isTrue();
        UUID secondSession = UUID.fromString("00000000-0000-0000-0000-000000000005");
        runtime.start(new ConversationRuntimePort.Context("project-a", "team-a", "worker-a", "task-a",
                secondSession));
        assertThatThrownBy(() -> runtime.send(new ConversationRuntimePort.Message(
                secondSession, "message-2", "hello")))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.RESOURCE_EXHAUSTED));
        releaseResponse.countDown();
        runtime.close();
    }

    @Test
    void reportsEventHistoryExhaustionInsteadOfDroppingEvents() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "data: {\"status\":\"in_progress\"}\n\n"
                        + "data: {\"status\":\"in_progress\"}\n\n"));
        server.start();
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "agent-a", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 8192, "agentteams", "console", 1, 2, 10);
        QwenPawConversationRuntime runtime = new QwenPawConversationRuntime(configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), MAPPER);
        runtime.start(CONTEXT);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));

        awaitEvents(runtime, 3);
        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.delta", "conversation.failed");
        assertThat(runtime.events(SESSION_ID, 0).get(2).data()).contains("RESOURCE_EXHAUSTED");
        runtime.close();
    }

    @Test
    void enforcesConfiguredSessionLimit() throws Exception {
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "agent-a", null,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 8192, "agentteams", "console", 1, 10, 1);
        QwenPawConversationRuntime runtime = new QwenPawConversationRuntime(configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), MAPPER);
        runtime.start(CONTEXT);
        assertThatThrownBy(() -> runtime.start(new ConversationRuntimePort.Context(
                "project-a", "team-a", "worker-a", "task-a",
                UUID.fromString("00000000-0000-0000-0000-000000000005"))))
                .isInstanceOf(ConversationRuntimeException.class)
                .satisfies(error -> assertThat(((ConversationRuntimeException) error).code())
                        .isEqualTo(ConversationRuntimeException.Code.RESOURCE_EXHAUSTED));
        runtime.close();
    }

    @Test
    void removesCompletedRequestSoTheSessionCanSendAgain() throws Exception {
        server.createContext("/api/console/chat", exchange -> writeResponse(exchange, 200,
                "text/event-stream", "data: {\"status\":\"completed\"}\n\n"));
        server.start();
        QwenPawConversationRuntime runtime = runtime(null, 8192);
        runtime.start(CONTEXT);

        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        awaitEvents(runtime, 2);
        runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-2", "again"));
        awaitEvents(runtime, 3);

        assertThat(runtime.events(SESSION_ID, 0)).extracting(ConversationEvent::type)
                .containsExactly("conversation.started", "message.completed", "message.completed");
        runtime.close();
    }

    private QwenPawConversationRuntime runtime(String token, long maxResponseBytes) {
        return runtime(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                token, maxResponseBytes, Duration.ofSeconds(2));
    }

    private QwenPawConversationRuntime runtime(URI endpoint, String token, long maxResponseBytes,
            Duration requestTimeout) {
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                endpoint, "agent-a", token, Duration.ofSeconds(2), requestTimeout, maxResponseBytes,
                "agentteams", "console");
        return new QwenPawConversationRuntime(configuration,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), MAPPER);
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        agentId.set(exchange.getRequestHeaders().getFirst("X-Agent-Id"));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
    }

    private static void writeResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void awaitEvents(QwenPawConversationRuntime runtime, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runtime.events(SESSION_ID, 0).size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(runtime.events(SESSION_ID, 0)).hasSizeGreaterThanOrEqualTo(expected);
    }
}
