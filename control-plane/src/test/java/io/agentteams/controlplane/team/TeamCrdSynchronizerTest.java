package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.domain.agent.AgentPhase;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TeamCrdSynchronizerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID FIRST_AGENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_AGENT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private FoundationPersistenceService persistence;
    private TeamCrdSynchronizer synchronizer;

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        org.postgresql.ds.PGSimpleDataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        persistence = new FoundationPersistenceService(dataSource);
        synchronizer = new TeamCrdSynchronizer(persistence, new TeamCrdParser(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        persistence.inTransaction(tx -> {
            tx.agents().insert(agent(FIRST_AGENT, "lead"));
            tx.agents().insert(agent(SECOND_AGENT, "worker"));
            return null;
        });
    }

    @Test
    void appliesTeamSnapshotIdempotentlyAndReplacesMembers() {
        GenericKubernetesResource first = resource("42", List.of(member(FIRST_AGENT, "lead")));
        synchronizer.apply(first);
        synchronizer.apply(first);

        UUID teamId = TeamCrdParser.stableId("agentteams", "platform");
        persistence.inTransaction(tx -> {
            assertThat(tx.teams().findById(teamId)).get().satisfies(team -> {
                assertThat(team.name()).isEqualTo("agentteams/platform");
                assertThat(team.status()).isEqualTo("ACTIVE");
            });
            assertThat(tx.teams().activeMembers(teamId)).extracting(member -> member.agentId())
                    .containsExactly(FIRST_AGENT);
            assertThat(tx.teams().findPolicy(teamId)).get()
                    .extracting(policy -> policy.maxConcurrentTasks()).isEqualTo(2);
            assertThat(tx.teams().allMembers(teamId)).hasSize(1);
            return null;
        });

        synchronizer.apply(resource("43", List.of(member(SECOND_AGENT, "worker"))));

        persistence.inTransaction(tx -> {
            assertThat(tx.teams().activeMembers(teamId)).extracting(member -> member.agentId())
                    .containsExactly(SECOND_AGENT);
            assertThat(tx.teams().allMembers(teamId)).hasSize(2);
            return null;
        });
    }

    @Test
    void rejectsSnapshotWhenMemberAgentDoesNotExistWithoutPartialTeamWrite() {
        GenericKubernetesResource resource = resource("99",
                List.of(member(UUID.fromString("00000000-0000-0000-0000-000000000099"), "missing")));

        assertThatThrownBy(() -> synchronizer.apply(resource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent");

        persistence.inTransaction(tx -> {
            assertThat(tx.teams().findById(TeamCrdParser.stableId("agentteams", "platform"))).isEmpty();
            return null;
        });
    }

    @Test
    void marksDeletedTeamAndDeactivatesMembers() {
        GenericKubernetesResource resource = resource("42", List.of(member(FIRST_AGENT, "lead")));
        synchronizer.apply(resource);
        synchronizer.delete(resource);

        persistence.inTransaction(tx -> {
            assertThat(tx.teams().findById(TeamCrdParser.stableId("agentteams", "platform"))).get()
                    .extracting(team -> team.status()).isEqualTo("DELETED");
            assertThat(tx.teams().activeMembers(TeamCrdParser.stableId("agentteams", "platform"))).isEmpty();
            return null;
        });
    }

    private static AgentRecord agent(UUID id, String name) {
        return AgentRecord.create(id, name, AgentPhase.READY, "qwenpaw", "{\"python\":true}", NOW);
    }

    private static Map<String, Object> member(UUID agentId, String role) {
        return Map.of("agentRef", agentId.toString(), "role", role, "capabilities", List.of("python"));
    }

    private static GenericKubernetesResource resource(String resourceVersion, List<Map<String, Object>> members) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxConcurrentTasks", 2);
        policy.put("requireApproval", false);
        policy.put("allowedRuntimes", List.of("qwenpaw"));
        policy.put("requiredCapabilities", List.of("python"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("leaderRef", FIRST_AGENT.toString());
        spec.put("members", members);
        spec.put("policy", policy);
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("Team");
        resource.setMetadata(new ObjectMetaBuilder().withNamespace("agentteams").withName("platform")
                .withResourceVersion(resourceVersion).build());
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of("spec", spec)));
        return resource;
    }
}
