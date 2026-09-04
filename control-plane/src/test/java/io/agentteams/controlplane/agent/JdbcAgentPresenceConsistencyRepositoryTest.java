package io.agentteams.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentPresenceConsistencyRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private JdbcAgentPresenceConsistencyRepository repository;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration")
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAgentPresenceConsistencyRepository(jdbc);
    }

    @Test
    void downgradesReadyAgentsTheGatewayReportsOffline() {
        UUID agentId = agent("READY");
        presence(agentId, "OFFLINE", NOW.minusSeconds(10));

        assertThat(reconcile(NOW)).isEqualTo(1);
        assertThat(phase(agentId)).isEqualTo("OFFLINE");
    }

    @Test
    void keepsReadyAgentsTheGatewayReportedSeenAtOrAfterTheCutoff() {
        UUID agentId = agent("READY");
        presence(agentId, "ONLINE", NOW);

        assertThat(reconcile(NOW)).isZero();
        assertThat(phase(agentId)).isEqualTo("READY");
    }

    @Test
    void downgradesOnlineAgentsWhoseHeartbeatsStoppedBeforeTheCutoff() {
        UUID agentId = agent("READY");
        presence(agentId, "ONLINE", NOW.minusSeconds(300));

        assertThat(reconcile(NOW.minusSeconds(60))).isEqualTo(1);
        assertThat(phase(agentId)).isEqualTo("OFFLINE");
    }

    @Test
    void leavesAgentsThatAreNotReadyAlone() {
        UUID draining = agent("DRAINING");
        presence(draining, "OFFLINE", NOW.minusSeconds(10));
        UUID terminated = agent("TERMINATED");
        presence(terminated, "OFFLINE", NOW.minusSeconds(10));

        assertThat(reconcile(NOW)).isZero();
        assertThat(phase(draining)).isEqualTo("DRAINING");
        assertThat(phase(terminated)).isEqualTo("TERMINATED");
    }

    @Test
    void leavesReadyAgentsThatNeverConnectedAlone() {
        UUID agentId = agent("READY");

        assertThat(reconcile(NOW)).isZero();
        assertThat(phase(agentId)).isEqualTo("READY");
    }

    @Test
    void refusesToDowngradeAnAgentThatChangedPhaseAfterItWasSelected() {
        UUID agentId = agent("TERMINATED");

        assertThat(repository.markOffline(agentId, NOW)).isZero();
        assertThat(phase(agentId)).isEqualTo("TERMINATED");
    }

    private int reconcile(Instant lastSeenBefore) {
        int repaired = 0;
        for (UUID agentId : repository.findStaleReadyAgents(lastSeenBefore, 100)) {
            repaired += repository.markOffline(agentId, NOW);
        }
        return repaired;
    }

    private UUID agent(String phase) {
        UUID agentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents(id, name, phase, runtime, capabilities, metadata, created_at, updated_at)
                VALUES (?, ?, ?, 'qwenpaw', '{}'::jsonb, '{}'::jsonb, ?, ?)
                """, agentId, "agent-" + agentId, phase, Timestamp.from(NOW), Timestamp.from(NOW));
        return agentId;
    }

    private void presence(UUID agentId, String presence, Instant lastSeenAt) {
        jdbc.update("""
                INSERT INTO gateway_agent_state(agent_id, presence, phase, runtime, runtime_version,
                    capabilities, last_seen_at, updated_at)
                VALUES (?, ?, 'READY', 'qwenpaw', '1', '{}'::jsonb, ?, ?)
                """, agentId.toString(), presence, Timestamp.from(lastSeenAt), Timestamp.from(lastSeenAt));
    }

    private String phase(UUID agentId) {
        List<String> phases = jdbc.query("SELECT phase FROM agents WHERE id = ?",
                (rs, row) -> rs.getString("phase"), agentId);
        return phases.isEmpty() ? null : phases.get(0);
    }
}
