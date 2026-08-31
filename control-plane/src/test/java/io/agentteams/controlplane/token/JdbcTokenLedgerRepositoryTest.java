package io.agentteams.controlplane.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

@Testcontainers(disabledWithoutDocker = true)
class JdbcTokenLedgerRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static final TokenLedgerScope SCOPE = new TokenLedgerScope("org-1", "tenant-1", "project-1");
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @BeforeAll
    static void migrate() {
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM token_ledger_entries");
        jdbc.update("DELETE FROM token_ledger_reservations");
    }

    @AfterAll
    static void closeDataSource() { }

    @Test
    void persistsFactsAndScopesReads() {
        JdbcTokenLedgerRepository repository = new JdbcTokenLedgerRepository(jdbc);
        TokenLedgerService service = new TokenLedgerService(repository);
        TokenReservation reservation = service.reserve(new TokenLedgerService.ReserveRequest(SCOPE, null, null,
                100, "manager", "model-a", "reserve-1"), NOW);
        service.settle(SCOPE, reservation.id(), 73, "settle-1", "worker", "model-a", NOW.plusSeconds(1));

        assertThat(service.find(SCOPE, reservation.id()).state()).isEqualTo(TokenReservation.State.SETTLED);
        assertThat(service.entries(SCOPE, reservation.id())).extracting(TokenLedgerEntry::kind)
                .containsExactly(TokenLedgerEntry.Kind.RESERVED, TokenLedgerEntry.Kind.SETTLED);
        assertThat(repository.find(new TokenLedgerScope("org-1", "tenant-2", "project-1"), reservation.id()))
                .isEmpty();
    }

    @Test
    void databaseUniqueIndexMakesReserveIdempotencyScopeSafeWhenProjectIsNull() {
        JdbcTokenLedgerRepository repository = new JdbcTokenLedgerRepository(jdbc);
        TokenLedgerService service = new TokenLedgerService(repository);
        TokenLedgerScope orgScope = new TokenLedgerScope("org-1", "tenant-1", null);
        TokenReservation first = service.reserve(new TokenLedgerService.ReserveRequest(orgScope, UUID.randomUUID(), null,
                100, "manager", "model-a", "reserve-1"), NOW);
        TokenReservation repeated = service.reserve(new TokenLedgerService.ReserveRequest(orgScope, first.taskId(), null,
                100, "manager", "model-a", "reserve-1"), NOW.plusSeconds(1));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_ledger_entries", Integer.class)).isEqualTo(1);
    }
}
