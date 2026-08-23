package io.agentteams.runtime;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.AcquireQuotaRequest;
import io.agentteams.contracts.v1.AcquireQuotaResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.QuotaProtocolError;
import io.agentteams.contracts.v1.QuotaRejectionDimension;
import io.agentteams.contracts.v1.QuotaServiceGrpc;
import io.agentteams.contracts.v1.ReleaseQuotaRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import io.grpc.ManagedChannel;

/** Runtime quota port backed by the Gateway quota gRPC service. */
public final class GrpcRuntimeQuotaPort implements RuntimeQuotaPort, AutoCloseable {
    private static final ProtocolVersion VERSION = ProtocolVersion.newBuilder().setMajor(2).setMinor(3).build();

    private final ManagedChannel channel;
    private final QuotaServiceGrpc.QuotaServiceBlockingStub stub;
    private final Clock clock;
    private final Duration timeout;
    private final Supplier<String> traceparent;
    private final String agentId;

    public GrpcRuntimeQuotaPort(ManagedChannel channel, String agentId) {
        this(channel, agentId, Clock.systemUTC(), Duration.ofSeconds(3), () -> "");
    }

    public GrpcRuntimeQuotaPort(ManagedChannel channel, String agentId, Clock clock,
            Duration timeout, Supplier<String> traceparent) {
        this(channel, QuotaServiceGrpc.newBlockingStub(Objects.requireNonNull(channel, "channel")), agentId,
                clock, timeout, traceparent);
    }

    GrpcRuntimeQuotaPort(ManagedChannel channel, QuotaServiceGrpc.QuotaServiceBlockingStub stub,
            String agentId, Clock clock, Duration timeout, Supplier<String> traceparent) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.stub = Objects.requireNonNull(stub, "stub");
        this.agentId = requireText(agentId, "agentId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timeout = validateTimeout(timeout);
        this.traceparent = Objects.requireNonNull(traceparent, "traceparent");
    }

    @Override
    public RuntimeQuotaLease acquire(String tenantId, String projectId, long estimatedTokens) {
        requireText(tenantId, "tenantId");
        requireText(projectId, "projectId");
        if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
        String idempotencyKey = UUID.randomUUID().toString();
        AcquireQuotaResponse response = stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .acquire(AcquireQuotaRequest.newBuilder()
                        .setMetadata(metadata())
                        .setProtocolVersion(VERSION)
                        .setTenantId(tenantId).setProjectId(projectId)
                        .setIdempotencyKey(idempotencyKey)
                        .setEstimatedTokens(estimatedTokens).setMaxConcurrent(1)
                        .setDeadline(deadline()).build());
        if (response.getProtocolError() != QuotaProtocolError.QUOTA_PROTOCOL_ERROR_UNSPECIFIED) {
            throw new IllegalStateException("quota acquire failed: " + response.getProtocolError());
        }
        if (!response.getAccepted()) {
            throw new RuntimeQuotaRejectedException(dimension(response.getRejectionDimension()));
        }
        if (response.getReservationId().isBlank()) throw new IllegalStateException("quota reservation_id is required");
        return RuntimeQuotaLease.idempotent(() -> release(tenantId, projectId, response.getReservationId()));
    }

    private void release(String tenantId, String projectId, String reservationId) {
        var response = stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .release(ReleaseQuotaRequest.newBuilder()
                        .setMetadata(metadata()).setProtocolVersion(VERSION)
                        .setTenantId(tenantId).setProjectId(projectId).setReservationId(reservationId)
                        .setIdempotencyKey(UUID.randomUUID().toString()).setDeadline(deadline()).build());
        if (response.getProtocolError() != QuotaProtocolError.QUOTA_PROTOCOL_ERROR_UNSPECIFIED
                || !response.getAccepted()) {
            throw new IllegalStateException("quota release failed: " + response.getProtocolError());
        }
    }

    private EventMetadata metadata() {
        return EventMetadata.newBuilder().setEventId(UUID.randomUUID().toString()).setAgentId(agentId)
                .setTraceparent(nullToEmpty(traceparent.get())).build();
    }

    private Timestamp deadline() {
        var at = clock.instant().plus(timeout);
        return Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
    }

    private static String dimension(QuotaRejectionDimension value) {
        return switch (value) {
            case QUOTA_REJECTION_DIMENSION_CONCURRENT_CALLS -> "concurrent_calls";
            case QUOTA_REJECTION_DIMENSION_DAILY_CALLS -> "daily_calls";
            case QUOTA_REJECTION_DIMENSION_DAILY_TOKENS -> "daily_tokens";
            default -> "quota_limit";
        };
    }

    @Override
    public void close() { channel.shutdown(); }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Duration validateTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout");
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("timeout must be positive");
        return value;
    }
}
