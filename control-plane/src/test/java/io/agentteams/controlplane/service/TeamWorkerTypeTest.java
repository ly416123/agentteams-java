package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.AgentRepository;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.IdempotencyKeyRepository;
import io.agentteams.controlplane.persistence.TeamRepository;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamWorkerTypeTest {

    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Mock
    private FoundationPersistenceService persistence;
    @Mock
    private FoundationTransaction transaction;
    @Mock
    private TeamRepository teams;
    @Mock
    private AgentRepository agents;
    @Mock
    private IdempotencyKeyRepository idempotencyKeys;
    @Mock
    private ResourceScopeRepository resourceScopes;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("team:write", "agent:read")));
        when(transaction.teams()).thenReturn(teams);
        when(transaction.agents()).thenReturn(agents);
        when(transaction.idempotencyKeys()).thenReturn(idempotencyKeys);
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void rejectsExecutorWhenAssignedAsTeamLeader() {
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(idempotencyKeys.findByKey("member-key")).thenReturn(Optional.empty());
        when(teams.findById(teamId)).thenReturn(Optional.of(new io.agentteams.controlplane.persistence.TeamRecord(
                teamId, "team-a", "Team A", "ACTIVE", NOW, NOW, 0)));
        when(agents.findById(agentId)).thenReturn(Optional.of(AgentRecord.create(agentId, "worker-a",
                WorkerType.EXECUTOR, AgentPhase.READY, "qwenpaw", "{}", NOW)));

        TeamService service = new TeamService(persistence,
                new io.agentteams.controlplane.team.TeamSchedulingPolicy(), resourceScopes,
                new IdempotencyService());

        assertThatThrownBy(() -> service.addMember(teamId, agentId, "LEADER", NOW, "member-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WORKER_TYPE_NOT_ALLOWED_FOR_ROLE");
    }

    @Test
    void acceptsLeaderWorkerWhenAssignedAsTeamLeader() {
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(idempotencyKeys.findByKey("leader-key")).thenReturn(Optional.empty());
        when(idempotencyKeys.insertIfAbsent(any())).thenReturn(true);
        when(teams.findById(teamId)).thenReturn(Optional.of(new io.agentteams.controlplane.persistence.TeamRecord(
                teamId, "team-a", "Team A", "ACTIVE", NOW, NOW, 0)));
        when(agents.findById(agentId)).thenReturn(Optional.of(AgentRecord.create(agentId, "leader-a",
                WorkerType.LEADER, AgentPhase.READY, "qwenpaw", "{}", NOW)));

        TeamService service = new TeamService(persistence,
                new io.agentteams.controlplane.team.TeamSchedulingPolicy(), resourceScopes,
                new IdempotencyService());

        assertThat(service.addMember(teamId, agentId, "LEADER", NOW, "leader-key").role()).isEqualTo("LEADER");
    }
}
