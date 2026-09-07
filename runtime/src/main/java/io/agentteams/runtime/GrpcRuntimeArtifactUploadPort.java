package io.agentteams.runtime;

import io.agentteams.contracts.v1.CompleteArtifactUploadRequest;
import io.agentteams.contracts.v1.CompleteArtifactUploadResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.PrepareArtifactUploadRequest;
import io.agentteams.contracts.v1.PrepareArtifactUploadResponse;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.TaskArtifactServiceGrpc;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Moves artifact bytes from the Worker straight to object storage through the
 * Gateway artifact contract: request a presigned ticket, PUT the bytes, then
 * have the Control Plane verify and expose the stored object. The Gateway only
 * proxies the two small metadata calls and never carries artifact payloads.
 */
public final class GrpcRuntimeArtifactUploadPort implements RuntimeArtifactUploadPort {
    private static final ProtocolVersion VERSION = ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build();

    private final TaskArtifactServiceGrpc.TaskArtifactServiceBlockingStub stub;
    private final HttpClient httpClient;
    private final String agentId;
    private final Clock clock;
    private final Duration timeout;
    private final Supplier<String> traceparent;

    public GrpcRuntimeArtifactUploadPort(ManagedChannel channel, String agentId, Clock clock,
            Duration timeout, Supplier<String> traceparent) {
        this(TaskArtifactServiceGrpc.newBlockingStub(Objects.requireNonNull(channel, "channel")),
                HttpClient.newBuilder().connectTimeout(Objects.requireNonNull(timeout, "timeout")).build(),
                agentId, clock, timeout, traceparent);
    }

    GrpcRuntimeArtifactUploadPort(TaskArtifactServiceGrpc.TaskArtifactServiceBlockingStub stub,
            HttpClient httpClient, String agentId, Clock clock, Duration timeout, Supplier<String> traceparent) {
        this.stub = Objects.requireNonNull(stub, "stub");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.agentId = requireText(agentId, "agentId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.traceparent = Objects.requireNonNull(traceparent, "traceparent");
    }

    @Override
    public UploadedArtifact upload(String taskId, String attemptId, String name, String contentType,
            byte[] payload) {
        requireText(taskId, "taskId");
        requireText(attemptId, "attemptId");
        requireText(name, "name");
        requireText(contentType, "contentType");
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        String sha256 = sha256(payload);
        Instant deadline = clock.instant().plus(timeout.multipliedBy(4));
        PrepareArtifactUploadResponse prepared = stub
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .prepareTaskArtifactUpload(PrepareArtifactUploadRequest.newBuilder()
                        .setMetadata(metadata(taskId, attemptId, deadline))
                        .setProtocolVersion(VERSION)
                        .setTaskId(taskId).setAttemptId(attemptId).setAgentId(agentId)
                        .setName(name).setContentType(contentType)
                        .setSizeBytes(payload.length)
                        .setDeadline(timestamp(deadline))
                        .build());
        if (!prepared.getAccepted()) {
            throw new IllegalStateException("artifact upload was not accepted: " + prepared.getProtocolError());
        }
        putBytes(prepared.getUploadUrl(), contentType, payload);
        CompleteArtifactUploadResponse completed = stub
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .completeTaskArtifactUpload(CompleteArtifactUploadRequest.newBuilder()
                        .setMetadata(metadata(taskId, attemptId, clock.instant().plus(timeout.multipliedBy(4))))
                        .setProtocolVersion(VERSION)
                        .setTaskId(taskId).setAttemptId(attemptId).setAgentId(agentId)
                        .setName(name).setStorageKey(prepared.getStorageKey())
                        .setContentType(contentType)
                        .setSizeBytes(payload.length).setSha256(sha256)
                        .setDeadline(timestamp(clock.instant().plus(timeout.multipliedBy(4))))
                        .build());
        if (!completed.getAccepted()) {
            throw new IllegalStateException("artifact upload verification failed: " + completed.getProtocolError());
        }
        return new UploadedArtifact(prepared.getStorageKey(), prepared.getDownloadUrl());
    }

    private void putBytes(String uploadUrl, String contentType, byte[] payload) {
        try {
            HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(URI.create(uploadUrl))
                            .timeout(timeout)
                            .header("Content-Type", contentType)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("artifact PUT returned HTTP " + response.statusCode());
            }
        } catch (IOException error) {
            throw new IllegalStateException("artifact PUT failed", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("artifact PUT interrupted", interrupted);
        }
    }

    private EventMetadata metadata(String taskId, String attemptId, Instant deadline) {
        EventMetadata.Builder builder = EventMetadata.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setAgentId(agentId)
                .setTaskId(taskId)
                .setAttemptId(attemptId)
                .setExpectedVersion(0)
                .setOccurredAt(timestamp(clock.instant()));
        String trace = traceparent.get();
        if (trace != null && !trace.isBlank()) {
            builder.setTraceparent(trace);
        }
        return builder.build();
    }

    private static com.google.protobuf.Timestamp timestamp(Instant instant) {
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
