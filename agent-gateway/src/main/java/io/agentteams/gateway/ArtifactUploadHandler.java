package io.agentteams.gateway;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.agentteams.application.api.ArtifactUploadHttp;
import io.agentteams.contracts.v1.ArtifactProtocolError;
import io.agentteams.contracts.v1.CompleteArtifactUploadRequest;
import io.agentteams.contracts.v1.CompleteArtifactUploadResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.PrepareArtifactUploadRequest;
import io.agentteams.contracts.v1.PrepareArtifactUploadResponse;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.TaskArtifactServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * gRPC transport adapter for direct-to-object-storage artifact upload tickets.
 * The handler only proxies metadata: artifact bytes travel from the Worker
 * straight to object storage through the presigned URLs issued by the
 * Control Plane.
 */
public final class ArtifactUploadHandler extends TaskArtifactServiceGrpc.TaskArtifactServiceImplBase {
    private static final long DEFAULT_EXPIRY_SECONDS = 900;
    private static final long MAX_EXPIRY_SECONDS = 3600;
    private static final long MIN_EXPIRY_SECONDS = 60;

    private final ArtifactUploadBridge client;
    private final Clock clock;
    /** Transport credential reader; blank means the call carries no identity. */
    private final Supplier<String> transportIdentity;

    public ArtifactUploadHandler(ArtifactUploadBridge client) {
        this(client, Clock.systemUTC());
    }

    public ArtifactUploadHandler(ArtifactUploadBridge client, Clock clock) {
        this(client, clock, GrpcTransportIdentity::current);
    }

    ArtifactUploadHandler(ArtifactUploadBridge client, Clock clock, Supplier<String> transportIdentity) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transportIdentity = Objects.requireNonNull(transportIdentity, "transportIdentity");
    }

    @Override
    public void prepareTaskArtifactUpload(PrepareArtifactUploadRequest request,
            StreamObserver<PrepareArtifactUploadResponse> observer) {
        try {
            validatePrepare(request);
            requireTransportCredential();
            ArtifactUploadHttp.PrepareResponse prepared = client.prepare(new ArtifactUploadHttp.PrepareRequest(
                    request.getTaskId(), request.getAttemptId(), request.getAgentId(), request.getName(),
                    request.getContentType(),
                    request.hasDeadline() ? expirySeconds(request.getDeadline()) : DEFAULT_EXPIRY_SECONDS,
                    request.hasDeadline() ? deadlineInstant(request.getDeadline()) : null));
            observer.onNext(PrepareArtifactUploadResponse.newBuilder()
                    .setMetadata(request.getMetadata())
                    .setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(true)
                    .setStorageKey(prepared.storageKey())
                    .setUploadUrl(prepared.uploadUrl())
                    .setDownloadUrl(prepared.downloadUrl())
                    .build());
            observer.onCompleted();
        } catch (UnauthenticatedException error) {
            respondPrepare(request, observer, false, "", "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_UNAUTHENTICATED);
        } catch (IllegalArgumentException error) {
            respondPrepare(request, observer, false, "", "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INVALID_ARGUMENT);
        } catch (ControlPlaneArtifactUploadClient.ControlPlaneInvocationException error) {
            respondPrepare(request, observer, false, "", "", "",
                    invocationError(error));
        } catch (RuntimeException error) {
            respondPrepare(request, observer, false, "", "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INTERNAL);
        }
    }

    @Override
    public void completeTaskArtifactUpload(CompleteArtifactUploadRequest request,
            StreamObserver<CompleteArtifactUploadResponse> observer) {
        try {
            validateComplete(request);
            requireTransportCredential();
            ArtifactUploadHttp.CompleteResponse completed = client.complete(
                    new ArtifactUploadHttp.CompleteRequest(request.getTaskId(), request.getAttemptId(),
                            request.getAgentId(), request.getName(), request.getStorageKey(),
                            request.getContentType(), request.getSizeBytes(), request.getSha256(),
                            request.hasDeadline() ? deadlineInstant(request.getDeadline()) : null));
            observer.onNext(CompleteArtifactUploadResponse.newBuilder()
                    .setMetadata(request.getMetadata())
                    .setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(true)
                    .setArtifactId(completed.artifactId())
                    .setStorageKey(completed.storageKey())
                    .build());
            observer.onCompleted();
        } catch (UnauthenticatedException error) {
            respondComplete(request, observer, false, "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_UNAUTHENTICATED);
        } catch (IllegalArgumentException error) {
            respondComplete(request, observer, false, "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INVALID_ARGUMENT);
        } catch (ControlPlaneArtifactUploadClient.ControlPlaneInvocationException error) {
            respondComplete(request, observer, false, "", "", invocationError(error));
        } catch (RuntimeException error) {
            respondComplete(request, observer, false, "", "",
                    ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INTERNAL);
        }
    }

    /**
     * 公理二：unary 上传请求必须携带传输凭证（AgentChannel 的 HELLO 认证
     * 不适用独立 unary 调用，凭证非空是第一道身份闸门）。空凭证直接以
     * UNAUTHENTICATED 拒绝，不触碰 Control Plane。
     */
    private void requireTransportCredential() {
        String credential = transportIdentity.get();
        if (credential == null || credential.isBlank()) {
            throw new UnauthenticatedException();
        }
    }

    /** Distinct from IllegalArgumentException so it maps to UNAUTHENTICATED, not INVALID_ARGUMENT. */
    private static final class UnauthenticatedException extends RuntimeException {
    }

    private static ArtifactProtocolError invocationError(
            ControlPlaneArtifactUploadClient.ControlPlaneInvocationException error) {
        return switch (error.statusCode()) {
            case 400 -> ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INVALID_ARGUMENT;
            case 403 -> ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_ATTEMPT_MISMATCH;
            case 404 -> ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_UPLOAD_NOT_FOUND;
            default -> ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INTERNAL;
        };
    }

    private static void validatePrepare(PrepareArtifactUploadRequest request) {
        if (request == null || !request.hasMetadata() || !request.hasProtocolVersion()) {
            throw new IllegalArgumentException("metadata and protocol_version are required");
        }
        if (request.getMetadata().getEventId().isBlank()) {
            throw new IllegalArgumentException("event_id is required");
        }
        if (request.getProtocolVersion().getMajor() == 0) {
            throw new IllegalArgumentException("protocol major is required");
        }
        if (request.getTaskId().isBlank() || request.getAttemptId().isBlank() || request.getAgentId().isBlank()
                || request.getName().isBlank() || request.getContentType().isBlank()) {
            throw new IllegalArgumentException("task, attempt, agent, name and contentType are required");
        }
    }

    private static void validateComplete(CompleteArtifactUploadRequest request) {
        if (request == null || !request.hasMetadata() || !request.hasProtocolVersion()) {
            throw new IllegalArgumentException("metadata and protocol_version are required");
        }
        if (request.getMetadata().getEventId().isBlank()) {
            throw new IllegalArgumentException("event_id is required");
        }
        if (request.getProtocolVersion().getMajor() == 0) {
            throw new IllegalArgumentException("protocol major is required");
        }
        if (request.getTaskId().isBlank() || request.getAttemptId().isBlank() || request.getAgentId().isBlank()
                || request.getName().isBlank() || request.getStorageKey().isBlank()
                || request.getContentType().isBlank() || request.getSha256().isBlank()) {
            throw new IllegalArgumentException("task, attempt, agent, name, storageKey, contentType "
                    + "and sha256 are required");
        }
        if (request.getSizeBytes() < 0) {
            throw new IllegalArgumentException("size_bytes must not be negative");
        }
    }

    /** Presigns the PUT for roughly the worker-reported deadline, clamped to a sane window. */
    private long expirySeconds(Timestamp deadline) {
        if (deadline == null || !Timestamps.isValid(deadline)) {
            return DEFAULT_EXPIRY_SECONDS;
        }
        long seconds = Duration.between(clock.instant(),
                Instant.ofEpochSecond(deadline.getSeconds(), deadline.getNanos())).getSeconds();
        return Math.max(MIN_EXPIRY_SECONDS, Math.min(seconds, MAX_EXPIRY_SECONDS));
    }

    private static Instant deadlineInstant(Timestamp deadline) {
        return deadline == null ? null
                : Instant.ofEpochSecond(deadline.getSeconds(), deadline.getNanos());
    }

    private void respondPrepare(PrepareArtifactUploadRequest request,
            StreamObserver<PrepareArtifactUploadResponse> observer, boolean accepted,
            String storageKey, String uploadUrl, String downloadUrl, ArtifactProtocolError error) {
        EventMetadata metadata = request == null ? EventMetadata.getDefaultInstance() : request.getMetadata();
        ProtocolVersion version = request == null ? ProtocolVersion.getDefaultInstance()
                : request.getProtocolVersion();
        observer.onNext(PrepareArtifactUploadResponse.newBuilder()
                .setMetadata(metadata).setProtocolVersion(version)
                .setAccepted(accepted).setStorageKey(storageKey).setUploadUrl(uploadUrl)
                .setDownloadUrl(downloadUrl).setProtocolError(error)
                .build());
        observer.onCompleted();
    }

    private void respondComplete(CompleteArtifactUploadRequest request,
            StreamObserver<CompleteArtifactUploadResponse> observer, boolean accepted,
            String artifactId, String storageKey, ArtifactProtocolError error) {
        EventMetadata metadata = request == null ? EventMetadata.getDefaultInstance() : request.getMetadata();
        ProtocolVersion version = request == null ? ProtocolVersion.getDefaultInstance()
                : request.getProtocolVersion();
        observer.onNext(CompleteArtifactUploadResponse.newBuilder()
                .setMetadata(metadata).setProtocolVersion(version)
                .setAccepted(accepted).setArtifactId(artifactId).setStorageKey(storageKey)
                .setProtocolError(error)
                .build());
        observer.onCompleted();
    }
}
