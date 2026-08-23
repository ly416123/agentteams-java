package io.agentteams.application.api;

import java.time.Instant;

/** JSON contract used by the internal Gateway-to-Control-Plane quota bridge. */
public final class QuotaReservationHttp {
    private QuotaReservationHttp() {
    }

    public record AcquireRequest(String tenantId, String projectId, String idempotencyKey,
            long estimatedTokens, long maxConcurrent, Instant deadline,
            String traceparent, String tracestate) {
    }

    public record AcquireResponse(boolean accepted, String reservationId, String rejectionDimension,
            long retryAfterMillis, String protocolError) {
    }

    public record ReleaseRequest(String tenantId, String projectId, String reservationId,
            String idempotencyKey, Instant deadline, String traceparent, String tracestate) {
    }

    public record ReleaseResponse(boolean accepted, String reservationId, String protocolError) {
    }
}
