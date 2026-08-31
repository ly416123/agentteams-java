package io.agentteams.controlplane.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenLedgerServiceTest {
    private static final TokenLedgerScope SCOPE = new TokenLedgerScope("org-1", "tenant-1", "project-1");
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void reservesSettlesAndKeepsAppendOnlyFacts() {
        InMemoryTokenLedgerRepository repository = new InMemoryTokenLedgerRepository();
        TokenLedgerService service = new TokenLedgerService(repository);

        TokenReservation reserved = service.reserve(new TokenLedgerService.ReserveRequest(SCOPE,
                UUID.randomUUID(), UUID.randomUUID(), 100, "manager", "deepseek-chat", "reserve-1"), NOW);
        TokenReservation settled = service.settle(SCOPE, reserved.id(), 73, "settle-1", "manager", "deepseek-chat", NOW.plusSeconds(2));

        assertThat(reserved.state()).isEqualTo(TokenReservation.State.RESERVED);
        assertThat(settled.state()).isEqualTo(TokenReservation.State.SETTLED);
        assertThat(settled.settledTokens()).isEqualTo(73);
        assertThat(repository.entries).extracting(TokenLedgerEntry::kind)
                .containsExactly(TokenLedgerEntry.Kind.RESERVED, TokenLedgerEntry.Kind.SETTLED);
    }

    @Test
    void releaseIsTerminalAndSameOperationIsIdempotent() {
        InMemoryTokenLedgerRepository repository = new InMemoryTokenLedgerRepository();
        TokenLedgerService service = new TokenLedgerService(repository);
        TokenReservation reserved = service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null,
                100, "worker", "model-a", "reserve-1"), NOW);

        TokenReservation released = service.release(SCOPE, reserved.id(), "release-1", "worker", "model-a", NOW.plusSeconds(1));
        TokenReservation repeated = service.release(SCOPE, reserved.id(), "release-1", "worker", "model-a", NOW.plusSeconds(2));

        assertThat(released).isEqualTo(repeated);
        assertThat(released.state()).isEqualTo(TokenReservation.State.RELEASED);
        assertThat(repository.entries).extracting(TokenLedgerEntry::kind)
                .containsExactly(TokenLedgerEntry.Kind.RESERVED, TokenLedgerEntry.Kind.RELEASED);
        assertThatThrownBy(() -> service.settle(SCOPE, reserved.id(), 1, "settle-1", "worker", "model-a", NOW.plusSeconds(3)))
                .isInstanceOf(TokenLedgerConflictException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void rejectsScopeMismatchAndIdempotencyKeyReuseWithDifferentRequest() {
        InMemoryTokenLedgerRepository repository = new InMemoryTokenLedgerRepository();
        TokenLedgerService service = new TokenLedgerService(repository);
        service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null, 100, "manager", "model-a", "reserve-1"), NOW);

        assertThatThrownBy(() -> service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null,
                101, "manager", "model-a", "reserve-1"), NOW))
                .isInstanceOf(TokenLedgerConflictException.class)
                .hasMessageContaining("idempotency");
        assertThatThrownBy(() -> service.find(new TokenLedgerScope("org-1", "tenant-2", "project-1"),
                UUID.randomUUID()))
                .isInstanceOf(TokenLedgerNotFoundException.class);
    }

    @Test
    void rejectsInvalidTokenAmountsAndSensitiveAttribution() {
        TokenLedgerService service = new TokenLedgerService(new InMemoryTokenLedgerRepository());

        assertThatThrownBy(() -> service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null,
                -1, "manager", "model-a", "reserve-1"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null,
                1, "prompt=secret", "model-a", "reserve-2"), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    private static final class InMemoryTokenLedgerRepository implements TokenLedgerRepository {
        private final List<TokenLedgerEntry> entries = new ArrayList<>();
        private final java.util.Map<UUID, TokenReservation> reservations = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, String> operationHashes = new java.util.HashMap<>();

        @Override
        public TokenReservation reserve(TokenReservation reservation, TokenLedgerEntry entry, String requestHash) {
            String key = "reserve:" + reservation.scope().key() + ":" + reservation.reserveIdempotencyKey();
            String previous = operationHashes.putIfAbsent(key, requestHash);
            if (previous != null && !previous.equals(requestHash)) throw new TokenLedgerConflictException("idempotency key is already bound to a different request");
            if (previous != null) return reservations.values().stream().filter(value -> value.reserveIdempotencyKey().equals(reservation.reserveIdempotencyKey())).findFirst().orElseThrow();
            reservations.put(reservation.id(), reservation);
            entries.add(entry);
            return reservation;
        }

        @Override
        public TokenReservation transition(TokenLedgerScope scope, UUID id, TokenReservation.State expected,
                TokenReservation.State next, long settledTokens, String operationKey, String requestHash,
                TokenLedgerEntry entry, Instant now) {
            TokenReservation current = find(scope, id).orElseThrow(TokenLedgerNotFoundException::new);
            String key = next.name().toLowerCase() + ":" + scope.key() + ":" + operationKey;
            String previous = operationHashes.putIfAbsent(key, requestHash);
            if (previous != null && !previous.equals(requestHash)) throw new TokenLedgerConflictException("idempotency key is already bound to a different request");
            if (previous != null) return current;
            if (current.state() != expected) throw new TokenLedgerConflictException("reservation is terminal");
            TokenReservation updated = current.transition(next, settledTokens, operationKey, now);
            reservations.put(id, updated);
            entries.add(entry);
            return updated;
        }

        @Override
        public Optional<TokenReservation> find(TokenLedgerScope scope, UUID id) {
            return Optional.ofNullable(reservations.get(id)).filter(value -> value.scope().equals(scope));
        }

        @Override
        public List<TokenLedgerEntry> entries(TokenLedgerScope scope, UUID reservationId) {
            return entries.stream().filter(entry -> entry.scope().equals(scope) && entry.reservationId().equals(reservationId)).toList();
        }
    }
}
