package io.agentteams.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AgentHello;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.AgentReady;
import io.agentteams.contracts.v1.Ack;
import io.agentteams.contracts.v1.ConfigApplied;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.runtime.AgentChannelClient;
import io.agentteams.runtime.AgentChannelState;
import io.agentteams.runtime.AgentRuntimeContext;
import io.agentteams.runtime.CompletionStatus;
import io.agentteams.runtime.GrpcAgentChannelPort;
import io.agentteams.runtime.GatewayRuntimeAdapter;
import io.agentteams.runtime.QwenPawHttpRuntimeConfiguration;
import io.agentteams.runtime.QwenPawHttpRuntimePort;
import io.agentteams.runtime.QwenPawRuntime;
import io.agentteams.runtime.RuntimeResult;
import io.agentteams.runtime.RuntimeResultSink;
import io.agentteams.runtime.RuntimeSubmission;
import io.agentteams.runtime.RuntimeConfigCoordinator;
import io.agentteams.runtime.RuntimeConfigPrepared;
import io.agentteams.runtime.RuntimeConfigSnapshot;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges the AgentTeams gRPC AgentChannel to a QwenPaw HTTP/SSE runtime.
 *
 * <p>The worker owns no business state. PostgreSQL remains authoritative; the
 * worker only keeps in-flight runtime state and reports protocol events back
 * through the Gateway.</p>
 */
public final class QwenPawWorker implements AutoCloseable {
    private static final ProtocolVersion PROTOCOL_VERSION = ProtocolVersion.newBuilder()
            .setMajor(2).setMinor(3).build();

    private final WorkerConfiguration configuration;
    private final Clock clock;
    private final ManagedChannel gatewayChannel;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AgentChannelClient channelClient;
    private final GrpcAgentChannelPort channelPort;
    private final QwenPawRuntime runtime;
    private final ConfigManifestFetcher manifestFetcher;
    private final RuntimeConfigCoordinator configCoordinator;
    private final GatewayRuntimeAdapter runtimeAdapter;
    private final AgentHello hello;
    private final WorkerHealthServer healthServer;
    private final Map<UUID, Object> resultGates = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeResult> pendingResults = new ConcurrentHashMap<>();
    private final Set<UUID> runningTasks = ConcurrentHashMap.newKeySet();

    private QwenPawWorker(WorkerConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Clock.systemUTC();
        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-worker-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.gatewayChannel = ManagedChannelBuilder.forAddress(configuration.gatewayHost(), configuration.gatewayPort())
                .usePlaintext().build();
        this.channelPort = new GrpcAgentChannelPort(gatewayChannel, this::onServerMessage,
                this::onDisconnected);
        this.channelClient = new AgentChannelClient(configuration.agentId(), channelPort, clock,
                configuration.reconnectDelay());
        QwenPawHttpRuntimeConfiguration qwenPawConfiguration = new QwenPawHttpRuntimeConfiguration(
                URI.create(configuration.qwenPawEndpoint()), configuration.qwenPawAgentId(),
                configuration.qwenPawAuthorizationToken(), configuration.qwenPawConnectTimeout(),
                configuration.qwenPawUserId(), configuration.qwenPawChannel(), configuration.qwenPawConfigurationPath());
        this.runtime = new QwenPawRuntime(new QwenPawHttpRuntimePort(qwenPawConfiguration));
        this.manifestFetcher = new ConfigManifestFetcher(configuration.configManifestBaseUrl(),
                configuration.configFetchTimeout(), configuration.maxConfigManifestBytes());
        this.configCoordinator = new RuntimeConfigCoordinator((snapshot, current) ->
                new RuntimeConfigPrepared() {
                    @Override
                    public void activate() {
                        runtime.applyConfig(snapshot);
                    }

                    @Override
                    public void discard() {
                    }
                });
        this.runtimeAdapter = new GatewayRuntimeAdapter(configuration.agentId(), channelPort, runtime, clock);
        RuntimeResultSink resultSink = this::onRuntimeResult;
        this.hello = hello(configuration, clock);
        runtime.start(new AgentRuntimeContext("qwenpaw", configuration.maxConcurrentTasks(), clock,
                resultSink, configuration.runtimeConfiguration()));
        this.healthServer = new WorkerHealthServer(configuration.healthPort());
    }

    public static QwenPawWorker fromEnvironment() {
        return new QwenPawWorker(WorkerConfiguration.fromEnvironment());
    }

    public void start() {
        healthServer.start();
        channelPort.connect();
        channelClient.connect(hello);
        scheduler.scheduleAtFixedRate(this::heartbeatLeases, 5, 5, TimeUnit.SECONDS);
        System.out.printf("QwenPaw Worker started agent=%s gateway=%s:%d qwenpaw=%s%n",
                configuration.agentId(), configuration.gatewayHost(), configuration.gatewayPort(),
                configuration.qwenPawEndpoint());
    }

    public void awaitTermination() throws InterruptedException {
        while (!closed.get()) {
            Thread.sleep(1000);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            healthServer.close();
            runtime.stop();
            channelClient.close();
            channelPort.close();
            gatewayChannel.shutdownNow();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private void onServerMessage(ServerMessage message) {
        if (closed.get()) {
            return;
        }
        try {
            if (message.hasReady()) {
                onReady(message.getReady());
            } else if (message.hasTaskAssigned()) {
                onAssignment(message.getTaskAssigned());
            } else if (message.hasConfigChanged()) {
                onConfigChanged(message.getConfigChanged());
            } else if (message.hasError()) {
                System.err.printf("Agent Gateway error code=%s message=%s%n",
                        message.getError().getCode(), message.getError().getMessage());
            }
        } catch (RuntimeException error) {
            System.err.printf("Unable to process Agent Gateway message: %s%n", rootMessage(error));
        }
    }

    private void onConfigChanged(ConfigChanged changed) {
        EventMetadata input = changed.getMetadata();
        System.out.printf("ConfigChanged received agent=%s version=%d sequence=%d%n",
                configuration.agentId(), changed.getConfigVersion(), input.getSequence());
        boolean applied = false;
        String errorMessage = "";
        try {
            if (!configuration.agentId().equals(input.getAgentId())) {
                throw new IllegalArgumentException("config agent_id does not match Worker");
            }
            String manifestJson = changed.getManifestJson().isBlank()
                    ? manifestFetcher.fetch(changed.getManifestUri(), changed.getSnapshotId(),
                            changed.getManifestSha256(), changed.getSizeBytes())
                    : changed.getManifestJson();
            byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length != changed.getSizeBytes()) {
                throw new IllegalArgumentException("configuration manifest size mismatch");
            }
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes));
            if (!checksum.equalsIgnoreCase(changed.getManifestSha256())) {
                throw new IllegalArgumentException("configuration manifest checksum mismatch");
            }
            JsonNode root = new ObjectMapper().readTree(manifestJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("configuration manifest must be a JSON object");
            }
            Map<String, String> values = new java.util.LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().isTextual()
                    ? entry.getValue().asText() : entry.getValue().toString()));
            configCoordinator.apply(new RuntimeConfigSnapshot(changed.getConfigVersion(),
                    changed.getManifestSha256(), values));
            applied = true;
        } catch (Exception failure) {
            errorMessage = truncate(rootMessage(failure));
        }
        EventMetadata metadata = input.toBuilder()
                .setEventId(UUID.nameUUIDFromBytes(("config-applied:" + input.getEventId() + ":" + applied)
                        .getBytes(StandardCharsets.UTF_8)).toString())
                .setOccurredAt(timestamp(clock.instant()))
                .build();
        channelPort.send(AgentMessage.newBuilder().setConfigApplied(ConfigApplied.newBuilder()
                .setMetadata(metadata)
                .setConfigVersion(changed.getConfigVersion())
                .setApplied(applied)
                .setErrorMessage(errorMessage)
                .setBindingId(changed.getBindingId())
                .setSnapshotId(changed.getSnapshotId())
                .build()).build());
        System.out.printf("ConfigChanged completed agent=%s version=%d applied=%s error=%s%n",
                configuration.agentId(), changed.getConfigVersion(), applied, errorMessage);
        if (input.getSequence() > 0) {
            acknowledge(input);
        }
    }

    private void onReady(AgentReady ready) {
        channelClient.onReady(ready);
        if (ready.getAccepted()) {
            System.out.printf("QwenPaw Worker is READY agent=%s%n", configuration.agentId());
        } else {
            System.err.printf("QwenPaw Worker was rejected: %s%n", ready.getRejectionReason());
        }
    }

    private void onAssignment(TaskAssigned assignment) {
        UUID taskId = UUID.fromString(assignment.getMetadata().getTaskId());
        Object resultGate = resultGates.computeIfAbsent(taskId, ignored -> new Object());
        RuntimeSubmission submission;
        try {
            submission = channelClient.onTaskAssigned(assignment, runtimeAdapter);
            if (submission.accepted()) {
                RuntimeResult pending;
                synchronized (resultGate) {
                    if (!"already accepted".equals(submission.reason())) {
                        runtimeAdapter.progress(taskId, 0, "running", "QwenPaw execution started");
                        channelClient.advanceTaskEventVersion(taskId);
                    }
                    runningTasks.add(taskId);
                    pending = pendingResults.remove(taskId);
                }
                if (pending != null) {
                    reportRuntimeResult(pending);
                }
            } else {
                resultGates.remove(taskId, resultGate);
            }
        } catch (RuntimeException error) {
            // The assignment may already be expired or otherwise unprocessable.
            // Ack the durable delivery anyway so one poisoned command cannot
            // replay forever and block later assignments for this worker.
            acknowledge(assignment);
            resultGates.remove(taskId, resultGate);
            pendingResults.remove(taskId);
            System.err.printf("Task assignment task=%s rejected: %s%n",
                    assignment.getMetadata().getTaskId(), rootMessage(error));
            return;
        }
        System.out.printf("Task assignment task=%s accepted=%s%n",
                assignment.getMetadata().getTaskId(), submission.accepted());
        if (assignment.getMetadata().getSequence() > 0) {
            acknowledge(assignment);
        }
    }

    private void acknowledge(TaskAssigned assignment) {
        acknowledge(assignment.getMetadata());
    }

    private void acknowledge(EventMetadata input) {
        EventMetadata metadata = input.toBuilder()
                .setEventId("ack-" + input.getEventId())
                .setOccurredAt(timestamp(clock.instant()))
                .build();
        channelPort.send(AgentMessage.newBuilder().setAck(Ack.newBuilder()
                .setMetadata(metadata)
                .setAckedEventId(input.getEventId())
                .setAckedSequence(input.getSequence())
                .build()).build());
    }

    private void onRuntimeResult(RuntimeResult result) {
        UUID taskId = result.taskId();
        Object resultGate = resultGates.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (resultGate) {
            if (!runningTasks.contains(taskId)) {
                pendingResults.put(taskId, result);
                return;
            }
        }
        reportRuntimeResult(result);
    }

    private void reportRuntimeResult(RuntimeResult result) {
        try {
            CompletionStatus status = channelClient.completeTask(result, runtimeAdapter);
            System.out.printf("Task result task=%s success=%s status=%s output=%s%n",
                    result.taskId(), result.success(), status, truncate(result.output()));
            if (status == CompletionStatus.COMPLETED) {
                runningTasks.remove(result.taskId());
                pendingResults.remove(result.taskId());
                resultGates.remove(result.taskId());
            }
        } catch (RuntimeException error) {
            System.err.printf("Unable to report task result task=%s: %s%n",
                    result.taskId(), rootMessage(error));
        }
    }

    private void onDisconnected(Throwable error) {
        if (closed.get()) {
            return;
        }
        channelClient.onDisconnected();
        System.err.printf("Agent Gateway disconnected: %s%n", rootMessage(error));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            if (closed.get() || channelClient.state() != AgentChannelState.RECONNECTING) {
                return;
            }
            try {
                channelPort.connect();
                if (!channelClient.reconnectIfDue(hello)) {
                    channelPort.disconnect();
                    scheduleReconnect();
                }
            } catch (RuntimeException reconnectError) {
                channelPort.disconnect();
                System.err.printf("Agent Gateway reconnect failed: %s%n", rootMessage(reconnectError));
                scheduleReconnect();
            }
        }, configuration.reconnectDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void heartbeatLeases() {
        if (closed.get() || channelClient.state() != AgentChannelState.READY) {
            return;
        }
        channelClient.heartbeatAgent("idle");
        channelClient.heartbeatAll("running", runtimeAdapter::onHeartbeatSent);
    }

    static AgentHello hello(WorkerConfiguration configuration, Clock clock) {
        Instant now = clock.instant();
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAgentId(configuration.agentId())
                .setOccurredAt(timestamp(now)).build();
        return AgentHello.newBuilder()
                .setMetadata(metadata)
                .setProtocolVersion(PROTOCOL_VERSION)
                .setRuntimeName("qwenpaw")
                .setRuntimeVersion(configuration.runtimeVersion())
                .putAllCapabilities(Map.of("http-sse", "v1", "qwenpaw", "v1"))
                .setMaxConcurrentTasks(configuration.maxConcurrentTasks())
                .setMaxWorkspaceBytes(configuration.maxWorkspaceBytes())
                .setMaxArtifactBytes(configuration.maxArtifactBytes())
                .build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    record WorkerConfiguration(
            String agentId,
            String gatewayHost,
            int gatewayPort,
            String qwenPawEndpoint,
            String qwenPawAgentId,
            String qwenPawAuthorizationToken,
            String qwenPawUserId,
            String qwenPawChannel,
            String qwenPawConfigurationPath,
            Duration qwenPawConnectTimeout,
            Duration reconnectDelay,
            String runtimeVersion,
            int maxConcurrentTasks,
            long maxWorkspaceBytes,
            long maxArtifactBytes,
            int healthPort,
            URI configManifestBaseUrl,
            Duration configFetchTimeout,
            long maxConfigManifestBytes) {

        static WorkerConfiguration fromEnvironment() {
            return from(System.getenv());
        }

        static WorkerConfiguration from(Map<String, String> environment) {
            return new WorkerConfiguration(
                    required(environment, "AGENTTEAMS_AGENT_ID"),
                    value(environment, "AGENTTEAMS_GATEWAY_HOST", "agentteams-agentteams-java-gateway"),
                    integer(environment, "AGENTTEAMS_GATEWAY_PORT", 9090),
                    value(environment, "QWENPAW_ENDPOINT", "http://qwenpaw:8088"),
                    value(environment, "QWENPAW_AGENT_ID", "default"),
                    optional(environment, "QWENPAW_AUTH_TOKEN"),
                    value(environment, "QWENPAW_USER_ID", "agentteams"),
                    value(environment, "QWENPAW_CHANNEL", "console"),
                    value(environment, "QWENPAW_CONFIG_PATH", "/api/models/active"),
                    Duration.ofSeconds(integer(environment, "QWENPAW_CONNECT_TIMEOUT_SECONDS", 10)),
                    Duration.ofSeconds(integer(environment, "AGENTTEAMS_RECONNECT_DELAY_SECONDS", 2)),
                    value(environment, "AGENTTEAMS_RUNTIME_VERSION", "0.1.0"),
                    integer(environment, "AGENTTEAMS_MAX_CONCURRENT_TASKS", 1),
                    longValue(environment, "AGENTTEAMS_MAX_WORKSPACE_BYTES", 2L * 1024 * 1024 * 1024),
                    longValue(environment, "AGENTTEAMS_MAX_ARTIFACT_BYTES", 2L * 1024 * 1024 * 1024),
                    integer(environment, "AGENTTEAMS_WORKER_HEALTH_PORT", 9090),
                    optionalUri(environment, "AGENTTEAMS_CONFIG_MANIFEST_BASE_URL"),
                    Duration.ofSeconds(integer(environment, "AGENTTEAMS_CONFIG_FETCH_TIMEOUT_SECONDS", 15)),
                    longValue(environment, "AGENTTEAMS_MAX_CONFIG_MANIFEST_BYTES", 16L * 1024 * 1024));
        }

        Map<String, String> runtimeConfiguration() {
            return Map.of(
                    "gatewayHost", gatewayHost,
                    "gatewayPort", Integer.toString(gatewayPort),
                    "qwenPawEndpoint", qwenPawEndpoint,
                    "qwenPawAgentId", qwenPawAgentId,
                    "qwenPawConfigurationPath", qwenPawConfigurationPath);
        }

        private static String required(Map<String, String> environment, String name) {
            String result = optional(environment, name);
            if (result == null) {
                throw new IllegalArgumentException(name + " must be set");
            }
            return result;
        }

        private static String optional(Map<String, String> environment, String name) {
            String result = environment.get(name);
            return result == null || result.isBlank() ? null : result.trim();
        }

        private static URI optionalUri(Map<String, String> environment, String name) {
            String result = optional(environment, name);
            return result == null ? null : URI.create(result);
        }

        private static String value(Map<String, String> environment, String name, String fallback) {
            String result = optional(environment, name);
            return result == null ? fallback : result;
        }

        private static int integer(Map<String, String> environment, String name, int fallback) {
            String result = optional(environment, name);
            if (result == null) return fallback;
            int parsed = Integer.parseInt(result);
            if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
            return parsed;
        }

        private static long longValue(Map<String, String> environment, String name, long fallback) {
            String result = optional(environment, name);
            if (result == null) return fallback;
            long parsed = Long.parseLong(result);
            if (parsed <= 0) throw new IllegalArgumentException(name + " must be positive");
            return parsed;
        }
    }

    private static final class WorkerHealthServer implements AutoCloseable {
        private final int port;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ServerSocket serverSocket;

        private WorkerHealthServer(int port) {
            this.port = port;
        }

        private void start() {
            try {
                serverSocket = new ServerSocket(port);
                Thread acceptor = new Thread(this::accept, "qwenpaw-worker-health");
                acceptor.setDaemon(true);
                acceptor.start();
            } catch (IOException error) {
                throw new IllegalStateException("unable to bind worker health port " + port, error);
            }
        }

        private void accept() {
            while (!closed.get()) {
                try (Socket ignored = serverSocket.accept()) {
                    // TCP readiness only needs a successful accept. The
                    // Operator already probes this port for process liveness.
                } catch (IOException error) {
                    if (!closed.get()) {
                        System.err.printf("Worker health socket stopped: %s%n", rootMessage(error));
                    }
                    return;
                }
            }
        }

        @Override
        public void close() {
            closed.set(true);
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                    // Shutdown is best effort.
                }
            }
        }
    }
}
