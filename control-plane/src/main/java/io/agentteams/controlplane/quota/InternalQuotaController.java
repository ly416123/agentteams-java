package io.agentteams.controlplane.quota;

import io.agentteams.application.api.QuotaReservationHttp;
import io.agentteams.application.api.QuotaReservationPort;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Internal HTTP bridge for the separately deployed Gateway quota service.
 *
 * <p>The public quota API remains a policy/configuration API. Reservation
 * state continues to be owned by the Control Plane and is never recreated in
 * the Gateway process.</p>
 */
@RestController
@RequestMapping("/internal/v1/quota")
public final class InternalQuotaController {
    static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";

    private final QuotaReservationPort reservations;
    private final String internalToken;

    public InternalQuotaController(QuotaReservationPort reservations,
            @Value("${agentteams.quota.internal-token:}") String internalToken) {
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/acquire")
    public ResponseEntity<QuotaReservationHttp.AcquireResponse> acquire(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody QuotaReservationHttp.AcquireRequest request) {
        authorize(token);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        QuotaReservationPort.AcquireDecision decision = reservations.acquire(
                new QuotaReservationPort.AcquireRequest(request.tenantId(), request.projectId(),
                        request.idempotencyKey(), request.estimatedTokens(), request.maxConcurrent(),
                        request.deadline(), request.traceparent(), request.tracestate()));
        return ResponseEntity.ok(new QuotaReservationHttp.AcquireResponse(decision.accepted(),
                decision.reservationId(), decision.rejectionDimension(), decision.retryAfterMillis(),
                decision.protocolError()));
    }

    @PostMapping("/release")
    public ResponseEntity<QuotaReservationHttp.ReleaseResponse> release(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody QuotaReservationHttp.ReleaseRequest request) {
        authorize(token);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        QuotaReservationPort.ReleaseDecision decision = reservations.release(
                new QuotaReservationPort.ReleaseRequest(request.tenantId(), request.projectId(),
                        request.reservationId(), request.idempotencyKey(), request.deadline(),
                        request.traceparent(), request.tracestate()));
        return ResponseEntity.ok(new QuotaReservationHttp.ReleaseResponse(decision.accepted(),
                decision.reservationId(), decision.protocolError()));
    }

    private void authorize(String token) {
        if (internalToken.isBlank() || token == null || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal quota token rejected");
        }
    }
}
