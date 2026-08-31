package io.agentteams.controlplane.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Token accounting boundary. Reservation state is only a projection; the
 * repository appends one immutable fact for every accepted operation.
 */
@Service
public final class TokenLedgerService {
    private final TokenLedgerRepository repository;

    public TokenLedgerService(TokenLedgerRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Transactional
    public TokenReservation reserve(ReserveRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        String source = TokenLedgerEntry.safeAttribution(request.source(), "source");
        String model = TokenLedgerEntry.safeAttribution(request.model(), "model");
        TokenReservation reservation = new TokenReservation(UUID.randomUUID(), request.scope(), request.taskId(),
                request.runId(), request.estimatedTokens(), 0, TokenReservation.State.RESERVED,
                request.idempotencyKey(), null, null, now, now);
        TokenLedgerEntry entry = new TokenLedgerEntry(UUID.randomUUID(), reservation.id(), request.scope(),
                request.taskId(), request.runId(), TokenLedgerEntry.Kind.RESERVED, request.estimatedTokens(),
                request.idempotencyKey(), source, model, now);
        return repository.reserve(reservation, entry, hash(request.scope().key(), request.idempotencyKey(),
                String.valueOf(request.estimatedTokens()), source, model, request.taskId(), request.runId()));
    }

    @Transactional
    public TokenReservation settle(TokenLedgerScope scope, UUID reservationId, long actualTokens,
            String idempotencyKey, String source, String model, Instant now) {
        return transition(scope, reservationId, actualTokens, idempotencyKey, source, model,
                TokenReservation.State.SETTLED, TokenLedgerEntry.Kind.SETTLED, now);
    }

    @Transactional
    public TokenReservation release(TokenLedgerScope scope, UUID reservationId, String idempotencyKey,
            String source, String model, Instant now) {
        return transition(scope, reservationId, 0, idempotencyKey, source, model,
                TokenReservation.State.RELEASED, TokenLedgerEntry.Kind.RELEASED, now);
    }

    @Transactional(readOnly = true)
    public TokenReservation find(TokenLedgerScope scope, UUID reservationId) {
        return repository.find(Objects.requireNonNull(scope, "scope"), Objects.requireNonNull(reservationId, "reservationId"))
                .orElseThrow(TokenLedgerNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<TokenLedgerEntry> entries(TokenLedgerScope scope, UUID reservationId) {
        find(scope, reservationId);
        return List.copyOf(repository.entries(scope, reservationId));
    }

    private TokenReservation transition(TokenLedgerScope scope, UUID reservationId, long tokens,
            String idempotencyKey, String source, String model, TokenReservation.State next,
            TokenLedgerEntry.Kind kind, Instant now) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(now, "now");
        if (tokens < 0) throw new IllegalArgumentException("actualTokens must not be negative");
        String operationKey = required(idempotencyKey, "idempotencyKey");
        String safeSource = TokenLedgerEntry.safeAttribution(source, "source");
        String safeModel = TokenLedgerEntry.safeAttribution(model, "model");
        TokenReservation current = find(scope, reservationId);
        TokenLedgerEntry entry = new TokenLedgerEntry(UUID.randomUUID(), reservationId, scope, current.taskId(),
                current.runId(), kind, tokens, operationKey, safeSource, safeModel, now);
        return repository.transition(scope, reservationId, TokenReservation.State.RESERVED, next, tokens,
                operationKey, hash(scope.key(), reservationId, operationKey, String.valueOf(tokens), safeSource,
                        safeModel), entry, now);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String hash(Object... values) {
        try {
            String canonical = java.util.Arrays.stream(values).map(String::valueOf).reduce((left, right) -> left + "\u0000" + right).orElse("");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record ReserveRequest(TokenLedgerScope scope, UUID taskId, UUID runId, long estimatedTokens,
            String source, String model, String idempotencyKey) {
        public ReserveRequest {
            Objects.requireNonNull(scope, "scope");
            if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
            required(source, "source");
            required(model, "model");
            required(idempotencyKey, "idempotencyKey");
        }
    }
}
