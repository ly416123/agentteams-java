package io.agentteams.controlplane.token;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TokenLedgerRepository {
    TokenReservation reserve(TokenReservation reservation, TokenLedgerEntry entry, String requestHash);

    TokenReservation transition(TokenLedgerScope scope, UUID id, TokenReservation.State expected,
            TokenReservation.State next, long settledTokens, String operationKey, String requestHash,
            TokenLedgerEntry entry, Instant now);

    Optional<TokenReservation> find(TokenLedgerScope scope, UUID id);

    List<TokenLedgerEntry> entries(TokenLedgerScope scope, UUID reservationId);
}
