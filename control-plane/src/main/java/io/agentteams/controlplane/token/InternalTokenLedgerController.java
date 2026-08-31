package io.agentteams.controlplane.token;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Internal Manager/Worker settlement bridge; it never exposes prompts or model responses. */
@RestController
@RequestMapping("/internal/v1/token-ledger")
public final class InternalTokenLedgerController {
    static final String TOKEN_HEADER = "X-AgentTeams-Internal-Token";
    private final TokenLedgerService ledger;
    private final String internalToken;

    public InternalTokenLedgerController(TokenLedgerService ledger,
            @Value("${agentteams.token-ledger.internal-token:}") String internalToken) {
        this(ledger, internalToken, true);
    }

    InternalTokenLedgerController(TokenLedgerService ledger, String internalToken, boolean ignored) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @PostMapping("/reserve")
    public ResponseEntity<TokenReservationResponse> reserve(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @RequestBody ReserveRequest request) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("request body is required");
        TokenLedgerService.ReserveRequest input = new TokenLedgerService.ReserveRequest(scope(request.organizationId(),
                request.tenantId(), request.projectId()), request.taskId(), request.runId(), request.estimatedTokens(),
                request.source(), request.model(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(TokenReservationResponse.from(ledger.reserve(input, Instant.now())));
    }

    @PostMapping("/{reservationId}/settle")
    public TokenReservationResponse settle(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID reservationId, @RequestBody TransitionRequest request) {
        authorize(token);
        requireRequest(request);
        return TokenReservationResponse.from(ledger.settle(scope(request.organizationId(), request.tenantId(), request.projectId()),
                reservationId, request.actualTokens(), request.idempotencyKey(), request.source(), request.model(), Instant.now()));
    }

    @PostMapping("/{reservationId}/release")
    public TokenReservationResponse release(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID reservationId, @RequestBody TransitionRequest request) {
        authorize(token);
        requireRequest(request);
        return TokenReservationResponse.from(ledger.release(scope(request.organizationId(), request.tenantId(), request.projectId()),
                reservationId, request.idempotencyKey(), request.source(), request.model(), Instant.now()));
    }

    @GetMapping("/{reservationId}")
    public TokenReservationResponse get(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID reservationId, @RequestParam String organizationId,
            @RequestParam String tenantId, @RequestParam(required = false) String projectId) {
        authorize(token);
        return TokenReservationResponse.from(ledger.find(scope(organizationId, tenantId, projectId), reservationId));
    }

    private void authorize(String token) {
        if (internalToken.isBlank() || token == null || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal token ledger token rejected");
        }
    }

    private static TokenLedgerScope scope(String organizationId, String tenantId, String projectId) {
        return new TokenLedgerScope(organizationId, tenantId, projectId);
    }

    private static void requireRequest(Object request) {
        if (request == null) throw new IllegalArgumentException("request body is required");
    }

    public record ReserveRequest(String organizationId, String tenantId, String projectId, UUID taskId, UUID runId,
            long estimatedTokens, String source, String model, String idempotencyKey) { }

    public record TransitionRequest(String organizationId, String tenantId, String projectId, long actualTokens,
            String source, String model, String idempotencyKey) { }

    public record TokenReservationResponse(UUID reservationId, String organizationId, String tenantId, String projectId,
            UUID taskId, UUID runId, long estimatedTokens, long settledTokens, TokenReservation.State state,
            Instant createdAt, Instant updatedAt) {
        static TokenReservationResponse from(TokenReservation reservation) {
            return new TokenReservationResponse(reservation.id(), reservation.scope().organizationId(),
                    reservation.scope().tenantId(), reservation.scope().projectId(), reservation.taskId(), reservation.runId(),
                    reservation.estimatedTokens(), reservation.settledTokens(), reservation.state(),
                    reservation.createdAt(), reservation.updatedAt());
        }
    }
}
