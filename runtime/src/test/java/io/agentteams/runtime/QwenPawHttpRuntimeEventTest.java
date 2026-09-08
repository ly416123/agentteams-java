package io.agentteams.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QwenPawHttpRuntimeEventTest {

    private HttpServer server;
    private QwenPawHttpRuntimePort port;
    private final List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch terminal = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/console/chat", exchange -> {
            String body = String.join("\n", List.of(
                    "data: {\"type\":\"tool.started\",\"tool\":\"web_search\"}",
                    "",
                    "data: {\"type\":\"reasoning\",\"text\":\"internal thought\"}",
                    "",
                    "data: {\"type\":\"plugin_call_output\",\"tool\":\"web_search\",\"status\":\"success\"}",
                    "",
                    "data: {\"object\":\"response\",\"status\":\"completed\",\"output\":\"done\"}",
                    "",
                    "data: [DONE]",
                    ""));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        port = new QwenPawHttpRuntimePort(new QwenPawHttpRuntimeConfiguration(
                URI.create("http://localhost:" + server.getAddress().getPort()), "agent-1", null,
                Duration.ofSeconds(2), "user-1", "console", "/api/models/active", false));
        port.start(new AgentRuntimeContext("qwenpaw", 1, Clock.systemUTC(), result -> { }, Map.of()),
                result -> terminal.countDown());
        port.setEventSink(events::add);
    }

    @AfterEach
    void stop() {
        port.stop();
        server.stop(0);
    }

    @Test
    void reportsWhitelistedToolEventsAndDropsReasoning() throws Exception {
        port.submit(new RuntimeTask(UUID.randomUUID(), "qwenpaw", "{\"prompt\":\"hi\"}", Map.of()));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        // reasoning 被白名单丢弃；tool.started→tool.called、plugin_call_output→tool.finished。
        assertEquals(List.of("tool.called", "tool.finished"),
                events.stream().map(RuntimeEvent::eventType).toList());
        RuntimeEvent called = events.get(0);
        assertTrue(called.payloadJson().contains("\"tool\":\"web_search\""));
        RuntimeEvent finished = events.get(1);
        assertTrue(finished.payloadJson().contains("\"ok\":true"));
        assertTrue(finished.payloadJson().contains("\"elapsedMs\":"));
    }

    @Test
    void staysSilentWithoutSink() throws Exception {
        port.setEventSink(null);
        port.submit(new RuntimeTask(UUID.randomUUID(), "qwenpaw", "{\"prompt\":\"hi\"}", Map.of()));
        assertTrue(terminal.await(5, TimeUnit.SECONDS));
        assertEquals(0, events.size());
    }
}
