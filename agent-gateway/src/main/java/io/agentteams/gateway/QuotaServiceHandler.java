package io.agentteams.gateway;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.agentteams.application.api.QuotaReservationPort;
import io.agentteams.contracts.v1.AcquireQuotaRequest;
import io.agentteams.contracts.v1.AcquireQuotaResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.QuotaProtocolError;
import io.agentteams.contracts.v1.QuotaRejectionDimension;
import io.agentteams.contracts.v1.QuotaServiceGrpc;
import io.agentteams.contracts.v1.ReleaseQuotaRequest;
import io.agentteams.contracts.v1.ReleaseQuotaResponse;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Objects;

/** gRPC transport adapter for short-lived project quota reservations. */
public final class QuotaServiceHandler extends QuotaServiceGrpc.QuotaServiceImplBase {
    private final QuotaReservationPort reservations;

    public QuotaServiceHandler(QuotaReservationPort reservations) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
    }

    @Override
    public void acquire(AcquireQuotaRequest request, StreamObserver<AcquireQuotaResponse> observer) {
        try {
            Instant deadline = validateAcquire(request);
            QuotaReservationPort.AcquireDecision decision = reservations.acquire(
                    new QuotaReservationPort.AcquireRequest(request.getTenantId(), request.getProjectId(),
                            request.getIdempotencyKey(), request.getEstimatedTokens(), request.getMaxConcurrent(),
                            deadline, traceparent(request.getMetadata()), tracestate(request.getMetadata())));
            observer.onNext(AcquireQuotaResponse.newBuilder()
                    .setMetadata(request.getMetadata())
                    .setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(decision.accepted())
                    .setReservationId(decision.reservationId())
                    .setRejectionDimension(rejectionDimension(decision.rejectionDimension()))
                    .setRetryAfterMs(decision.retryAfterMillis())
                    .setProtocolError(protocolError(decision.protocolError()))
                    .build());
            observer.onCompleted();
        } catch (IllegalArgumentException error) {
            observer.onNext(AcquireQuotaResponse.newBuilder()
                    .setMetadata(request == null ? EventMetadata.getDefaultInstance() : request.getMetadata())
                    .setProtocolVersion(request == null ? ProtocolVersion.getDefaultInstance() : request.getProtocolVersion())
                    .setAccepted(false)
                    .setProtocolError(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INVALID_ARGUMENT)
                    .build());
            observer.onCompleted();
        } catch (RuntimeException error) {
            observer.onNext(AcquireQuotaResponse.newBuilder()
                    .setMetadata(request.getMetadata()).setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(false).setProtocolError(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INTERNAL).build());
            observer.onCompleted();
        }
    }

    @Override
    public void release(ReleaseQuotaRequest request, StreamObserver<ReleaseQuotaResponse> observer) {
        try {
            Instant deadline = validateRelease(request);
            QuotaReservationPort.ReleaseDecision decision = reservations.release(
                    new QuotaReservationPort.ReleaseRequest(request.getTenantId(), request.getProjectId(),
                            request.getReservationId(), request.getIdempotencyKey(), deadline,
                            traceparent(request.getMetadata()), tracestate(request.getMetadata())));
            observer.onNext(ReleaseQuotaResponse.newBuilder()
                    .setMetadata(request.getMetadata()).setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(decision.accepted()).setReservationId(decision.reservationId())
                    .setProtocolError(protocolError(decision.protocolError())).build());
            observer.onCompleted();
        } catch (IllegalArgumentException error) {
            observer.onNext(ReleaseQuotaResponse.newBuilder()
                    .setMetadata(request == null ? EventMetadata.getDefaultInstance() : request.getMetadata())
                    .setProtocolVersion(request == null ? ProtocolVersion.getDefaultInstance() : request.getProtocolVersion())
                    .setAccepted(false).setProtocolError(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INVALID_ARGUMENT).build());
            observer.onCompleted();
        } catch (RuntimeException error) {
            observer.onNext(ReleaseQuotaResponse.newBuilder()
                    .setMetadata(request.getMetadata()).setProtocolVersion(request.getProtocolVersion())
                    .setAccepted(false).setProtocolError(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INTERNAL).build());
            observer.onCompleted();
        }
    }

    private static Instant validateAcquire(AcquireQuotaRequest request) {
        if (request == null || !request.hasMetadata() || !request.hasProtocolVersion()) {
            throw new IllegalArgumentException("metadata and protocol_version are required");
        }
        validateMetadata(request.getMetadata());
        if (request.getProtocolVersion().getMajor() == 0) throw new IllegalArgumentException("protocol major is required");
        if (request.getTenantId().isBlank() || request.getProjectId().isBlank()
                || request.getIdempotencyKey().isBlank()) throw new IllegalArgumentException("scope and idempotency_key are required");
        if (request.getMaxConcurrent() == 0) throw new IllegalArgumentException("max_concurrent must be positive");
        if (!request.hasDeadline() || !Timestamps.isValid(request.getDeadline())) throw new IllegalArgumentException("deadline is required");
        return instant(request.getDeadline());
    }

    private static Instant validateRelease(ReleaseQuotaRequest request) {
        if (request == null || !request.hasMetadata() || !request.hasProtocolVersion()) {
            throw new IllegalArgumentException("metadata and protocol_version are required");
        }
        validateMetadata(request.getMetadata());
        if (request.getProtocolVersion().getMajor() == 0) throw new IllegalArgumentException("protocol major is required");
        if (request.getTenantId().isBlank() || request.getProjectId().isBlank()
                || request.getReservationId().isBlank() || request.getIdempotencyKey().isBlank()) {
            throw new IllegalArgumentException("scope, reservation_id and idempotency_key are required");
        }
        if (!request.hasDeadline() || !Timestamps.isValid(request.getDeadline())) throw new IllegalArgumentException("deadline is required");
        return instant(request.getDeadline());
    }

    private static void validateMetadata(EventMetadata metadata) {
        if (metadata.getEventId().isBlank()) throw new IllegalArgumentException("event_id is required");
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String traceparent(EventMetadata metadata) { return metadata.getTraceparent(); }
    private static String tracestate(EventMetadata metadata) { return metadata.getTracestate(); }

    private static QuotaRejectionDimension rejectionDimension(String value) {
        if (value == null || value.isBlank()) return QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_UNSPECIFIED;
        return switch (value) {
            case "concurrent_calls" -> QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_CONCURRENT_CALLS;
            case "daily_calls" -> QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_CALLS;
            case "daily_tokens" -> QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_TOKENS;
            default -> QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_UNSPECIFIED;
        };
    }

    private static QuotaProtocolError protocolError(String value) {
        if (value == null || value.isBlank()) return QuotaProtocolError.QUOTA_PROTOCOL_ERROR_UNSPECIFIED;
        try { return QuotaProtocolError.valueOf("QUOTA_PROTOCOL_ERROR_" + value.toUpperCase()); }
        catch (IllegalArgumentException ignored) { return QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INTERNAL; }
    }
}
