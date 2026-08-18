package io.agentteams.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonLinesQwenPawProcessPortTest {
    @Test
    void startsProcessSubmitsTaskAndPublishesResult() throws Exception {
        JsonLinesQwenPawProcessPort port = new JsonLinesQwenPawProcessPort(configuration());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<RuntimeResult> result = new AtomicReference<>();
        RuntimeTask task = task();

        port.start(context(), value -> {
            result.set(value);
            completed.countDown();
        });
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().taskId()).isEqualTo(task.id());
        assertThat(result.get().success()).isTrue();
        assertThat(result.get().output()).isEqualTo("qwenpaw-result");
        port.stop();
    }

    @Test
    void cancellationRemovesTaskWithoutTreatingLateResultAsCompletion() throws Exception {
        JsonLinesQwenPawProcessPort port = new JsonLinesQwenPawProcessPort(configuration("hold"));
        CountDownLatch completed = new CountDownLatch(1);
        RuntimeTask task = task();
        port.start(context(), value -> completed.countDown());
        port.submit(task);

        port.cancel(task.id());
        Thread.sleep(150);

        assertThat(completed.getCount()).isEqualTo(1);
        port.stop();
    }

    @Test
    void unexpectedProcessExitFailsInFlightTasks() throws Exception {
        JsonLinesQwenPawProcessPort port = new JsonLinesQwenPawProcessPort(configuration("exit"));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<RuntimeResult> result = new AtomicReference<>();
        RuntimeTask task = task();
        port.start(context(), value -> {
            result.set(value);
            completed.countDown();
        });
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().taskId()).isEqualTo(task.id());
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().output()).contains("exited");
        port.stop();
    }

    @Test
    void rejectsInvalidConfigurationAndLifecycleCalls() {
        assertThatThrownBy(() -> new JsonLinesQwenPawProcessPort(
                new QwenPawProcessConfiguration(List.of(), null, Map.of(), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");

        JsonLinesQwenPawProcessPort port = new JsonLinesQwenPawProcessPort(configuration());
        assertThatThrownBy(() -> port.submit(task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("started");
    }

    private static AgentRuntimeContext context() {
        return new AgentRuntimeContext("qwenpaw", 2,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), value -> { }, Map.of("model", "qwen"));
    }

    private static RuntimeTask task() {
        return new RuntimeTask(UUID.randomUUID(), "chat", "{\"prompt\":\"hello\"}", Map.of("source", "test"));
    }

    private static QwenPawProcessConfiguration configuration(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProtocolProcess.class.getName());
        command.addAll(List.of(arguments));
        return new QwenPawProcessConfiguration(command, null, Map.of(), Duration.ofSeconds(2));
    }

    public static final class ProtocolProcess {
        public static void main(String[] args) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    PrintWriter writer = new PrintWriter(System.out, true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode command = mapper.readTree(line);
                    if ("task".equals(command.path("type").asText())) {
                        if (List.of(args).contains("exit")) {
                            return;
                        }
                        if (List.of(args).contains("hold")) {
                            continue;
                        }
                        writer.println(mapper.createObjectNode()
                                .put("type", "result")
                                .put("taskId", command.path("taskId").asText())
                                .put("success", true)
                                .put("output", "qwenpaw-result"));
                    } else if ("exit".equals(command.path("control").asText())) {
                        return;
                    }
                }
            }
        }
    }
}
