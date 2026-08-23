package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;

/** Application boundary for a remote or local project quota reservation service. */
public interface QuotaReservationPort {

    AcquireDecision acquire(AcquireRequest request);

    ReleaseDecision release(ReleaseRequest request);

    record AcquireRequest(String tenantId, String projectId, String idempotencyKey,
            long estimatedTokens, long maxConcurrent, Instant deadline,
            String traceparent, String tracestate) {
        public AcquireRequest {
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(deadline, "deadline");
            if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
            if (maxConcurrent <= 0) throw new IllegalArgumentException("maxConcurrent must be positive");
            traceparent = traceparent == null ? "" : traceparent;
            tracestate = tracestate == null ? "" : tracestate;
        }
    }

    record ReleaseRequest(String tenantId, String projectId, String reservationId,
            String idempotencyKey, Instant deadline, String traceparent, String tracestate) {
        public ReleaseRequest {
            requireText(tenantId, "tenantId");
            requireText(projectId, "projectId");
            requireText(reservationId, "reservationId");
            requireText(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(deadline, "deadline");
            traceparent = traceparent == null ? "" : traceparent;
            tracestate = tracestate == null ? "" : tracestate;
        }
    }

    record AcquireDecision(boolean accepted, String reservationId, String rejectionDimension,
            long retryAfterMillis, String protocolError) {
        public AcquireDecision {
            reservationId = reservationId == null ? "" : reservationId;
            rejectionDimension = rejectionDimension == null ? "" : rejectionDimension;
            protocolError = protocolError == null ? "" : protocolError;
            if (retryAfterMillis < 0) throw new IllegalArgumentException("retryAfterMillis must not be negative");
            if (accepted && reservationId.isBlank()) {
                throw new IllegalArgumentException("accepted decision requires reservationId");
            }
        }
    }

    record ReleaseDecision(boolean accepted, String reservationId, String protocolError) {
        public ReleaseDecision {
            reservationId = reservationId == null ? "" : reservationId;
            protocolError = protocolError == null ? "" : protocolError;
        }
    }

    static QuotaReservationPort noop() {
        return new QuotaReservationPort() {
            @Override
            public AcquireDecision acquire(AcquireRequest request) {
                Objects.requireNonNull(request, "request");
                return new AcquireDecision(true, "noop-" + request.idempotencyKey(), "", 0, "");
            }

            @Override
            public ReleaseDecision release(ReleaseRequest request) {
                Objects.requireNonNull(request, "request");
                return new ReleaseDecision(true, request.reservationId(), "");
            }
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
