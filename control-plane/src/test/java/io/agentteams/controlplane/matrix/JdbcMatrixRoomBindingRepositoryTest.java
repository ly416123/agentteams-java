package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMatrixRoomBindingRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");
    private static JdbcTemplate jdbc;

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
        jdbc.update("DELETE FROM matrix_room_bindings");
    }

    @Test
    void savesAndLoadsBindingByTenantRoomAndPrimaryContext() {
        MatrixRoomBinding binding = binding(MatrixRoomBindingStatus.ACTIVE, "tenant-1", "!room:example.org",
                CONVERSATION_ID);
        JdbcMatrixRoomBindingRepository repository = new JdbcMatrixRoomBindingRepository(jdbc);

        repository.save(binding);

        assertThat(repository.findActiveByRoom("tenant-1", "!room:example.org")).contains(binding);
        assertThat(repository.findActiveByContext("tenant-1", MatrixRoomContextType.CONVERSATION,
                CONVERSATION_ID)).contains(binding);
        assertThat(repository.findActiveByRoom("tenant-2", "!room:example.org")).isEmpty();
    }

    @Test
    void keepsRevokedHistoryAndRejectsSecondActiveRoomOrContext() {
        JdbcMatrixRoomBindingRepository repository = new JdbcMatrixRoomBindingRepository(jdbc);
        repository.save(binding(MatrixRoomBindingStatus.REVOKED, "tenant-1", "!room:example.org", CONVERSATION_ID));
        MatrixRoomBinding active = binding(MatrixRoomBindingStatus.ACTIVE, "tenant-1", "!room:example.org",
                CONVERSATION_ID);
        repository.save(active);

        assertThat(repository.findActiveByRoom("tenant-1", "!room:example.org")).contains(active);
        assertThatThrownBy(() -> repository.save(binding(MatrixRoomBindingStatus.ACTIVE, "tenant-1",
                "!other:example.org", CONVERSATION_ID))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.save(binding(MatrixRoomBindingStatus.ACTIVE, "tenant-1",
                "!room:example.org", UUID.randomUUID()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM matrix_room_bindings", Integer.class)).isEqualTo(2);
    }

    @Test
    void listsByContextAndUpdatesStatusWithOptimisticVersion() {
        JdbcMatrixRoomBindingRepository repository = new JdbcMatrixRoomBindingRepository(jdbc);
        MatrixRoomBinding active = binding(MatrixRoomBindingStatus.ACTIVE, "tenant-1", "!room:example.org",
                CONVERSATION_ID);
        repository.save(active);

        assertThat(repository.findByTenant("tenant-1", MatrixRoomContextType.CONVERSATION, CONVERSATION_ID))
                .containsExactly(active);
        assertThat(repository.findById("tenant-1", active.id())).contains(active);

        MatrixRoomBinding revoked = active.withStatus(MatrixRoomBindingStatus.REVOKED);
        revoked = new MatrixRoomBinding(revoked.id(), revoked.organizationId(), revoked.tenantId(), revoked.roomId(),
                revoked.primaryContextType(), revoked.primaryContextId(), revoked.projectId(), revoked.teamId(),
                revoked.status(), revoked.createdBy(), revoked.createdAt(), NOW.plusSeconds(1), 1);
        repository.update(revoked, 0);

        assertThat(repository.findActiveByRoom("tenant-1", "!room:example.org")).isEmpty();
        assertThat(repository.findById("tenant-1", active.id())).contains(revoked);
    }

    private static MatrixRoomBinding binding(MatrixRoomBindingStatus status, String tenantId, String roomId,
            UUID contextId) {
        return new MatrixRoomBinding(UUID.randomUUID(), "org-1", tenantId, roomId,
                MatrixRoomContextType.CONVERSATION, contextId, PROJECT_ID, TEAM_ID, status, "alice", NOW, NOW, 0);
    }
}
