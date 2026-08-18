package io.agentteams.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Production process boundary for QwenPaw.
 *
 * <p>The process receives one JSON object per stdin line and writes result
 * objects to stdout. The protocol intentionally keeps the runtime-specific
 * process outside the Agent Gateway and can later be replaced by a sidecar
 * without changing the runtime SPI.</p>
 */
public final class JsonLinesQwenPawProcessPort implements QwenPawProcessPort {
    private final QwenPawProcessConfiguration configuration;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private Process process;
    private BufferedWriter writer;
    private RuntimeResultSink resultSink;
    private Clock clock;
    private ExecutorService ioExecutor;
    private volatile boolean stopping;

    public JsonLinesQwenPawProcessPort(QwenPawProcessConfiguration configuration) {
        this(configuration, new ObjectMapper());
    }

    JsonLinesQwenPawProcessPort(QwenPawProcessConfiguration configuration, ObjectMapper mapper) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public synchronized void start(AgentRuntimeContext context, RuntimeResultSink resultSink) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resultSink, "resultSink");
        if (process != null) {
            throw new IllegalStateException("QwenPaw process is already started");
        }
        ProcessBuilder builder = new ProcessBuilder(configuration.command());
        if (configuration.workingDirectory() != null) {
            builder.directory(configuration.workingDirectory().toFile());
        }
        builder.environment().putAll(configuration.environment());
        try {
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.resultSink = resultSink;
            this.clock = context.clock();
            stopping = false;
            ioExecutor = Executors.newFixedThreadPool(2, daemonThreadFactory());
            Process current = process;
            ioExecutor.submit(() -> readResults(current));
            ioExecutor.submit(() -> drainErrors(current));
            send(startCommand(context));
        } catch (IOException | RuntimeException error) {
            closeAfterStartFailure();
            throw new QwenPawProcessException("failed to start QwenPaw process", error);
        }
    }

    @Override
    public void submit(RuntimeTask task) {
        Objects.requireNonNull(task, "task");
        ObjectNode command = mapper.createObjectNode().put("type", "task")
                .put("taskId", task.id().toString()).put("taskType", task.taskType())
                .put("inputJson", task.inputJson());
        command.set("metadata", mapper.valueToTree(task.metadata()));
        inFlight.add(task.id());
        try {
            send(command);
        } catch (RuntimeException error) {
            inFlight.remove(task.id());
            throw error;
        }
    }

    @Override
    public void cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId");
        inFlight.remove(taskId);
        send(mapper.createObjectNode().put("type", "cancel").put("taskId", taskId.toString()));
    }

    @Override
    public synchronized void stop() {
        Process current = process;
        if (current == null) {
            return;
        }
        stopping = true;
        try {
            if (current.isAlive()) {
                try {
                    send(mapper.createObjectNode().put("control", "stop"));
                } catch (RuntimeException ignored) {
                    // The process may already have closed stdin during shutdown.
                }
            }
            closeWriter();
            if (current.isAlive() && !current.waitFor(configuration.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                current.destroy();
                if (current.isAlive()) {
                    current.destroyForcibly();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        } finally {
            inFlight.clear();
            shutdownExecutor();
            process = null;
            writer = null;
            resultSink = null;
            clock = null;
        }
    }

    private ObjectNode startCommand(AgentRuntimeContext context) {
        ObjectNode command = mapper.createObjectNode().put("control", "start")
                .put("runtimeName", context.runtimeName()).put("maxConcurrency", context.maxConcurrency());
        command.set("configuration", mapper.valueToTree(context.configuration()));
        return command;
    }

    private void readResults(Process current) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    handleLine(line);
                } catch (RuntimeException error) {
                    failInFlight("QwenPaw emitted invalid result: " + error.getMessage());
                }
            }
            int exitCode = current.waitFor();
            if (!stopping) {
                failInFlight("QwenPaw process exited with code " + exitCode);
            }
        } catch (IOException error) {
            if (!stopping) {
                failInFlight("QwenPaw process output failed: " + error.getMessage());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleLine(String line) {
        final JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (JsonProcessingException error) {
            failInFlight("QwenPaw emitted invalid JSON: " + error.getOriginalMessage());
            return;
        }
        if (!"result".equals(message.path("type").asText())) {
            return;
        }
        UUID taskId;
        try {
            taskId = UUID.fromString(message.path("taskId").asText());
        } catch (IllegalArgumentException error) {
            return;
        }
        if (!inFlight.remove(taskId)) {
            return;
        }
        Instant occurredAt = message.hasNonNull("occurredAt")
                ? Instant.parse(message.path("occurredAt").asText()) : now();
        publish(new RuntimeResult(taskId, message.path("success").asBoolean(false),
                message.path("output").asText(""), occurredAt));
    }

    private void failInFlight(String message) {
        for (UUID taskId : inFlight.toArray(UUID[]::new)) {
            if (inFlight.remove(taskId)) {
                publish(RuntimeResult.failure(taskId, message, now()));
            }
        }
    }

    private void publish(RuntimeResult result) {
        RuntimeResultSink sink = resultSink;
        if (sink != null) {
            try {
                sink.accept(result);
            } catch (RuntimeException ignored) {
                // A consumer failure must not terminate the process reader.
            }
        }
    }

    private void send(ObjectNode command) {
        synchronized (writeLock) {
            if (writer == null || process == null || !process.isAlive()) {
                throw new IllegalStateException("QwenPaw process is not started");
            }
            try {
                writer.write(mapper.writeValueAsString(command));
                writer.newLine();
                writer.flush();
            } catch (IOException error) {
                throw new QwenPawProcessException("failed to send command to QwenPaw", error);
            }
        }
    }

    private void drainErrors(Process current) {
        try (BufferedReader ignored = new BufferedReader(new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
            while (ignored.readLine() != null) {
                // Drain stderr so a verbose runtime cannot block on its error pipe.
            }
        } catch (IOException ignored) {
            // Process termination owns stderr closure.
        }
    }

    private void closeAfterStartFailure() {
        stopping = true;
        closeWriter();
        if (process != null) {
            process.destroyForcibly();
        }
        shutdownExecutor();
        process = null;
        writer = null;
        resultSink = null;
        clock = null;
    }

    private void closeWriter() {
        synchronized (writeLock) {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // Shutdown is already in progress.
                }
            }
        }
    }

    private void shutdownExecutor() {
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
            ioExecutor = null;
        }
    }

    private Instant now() {
        Clock current = clock;
        return current == null ? Instant.now() : current.instant();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-process-io");
            thread.setDaemon(true);
            return thread;
        };
    }
}
