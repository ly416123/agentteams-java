package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.AgentRepository;
import io.agentteams.controlplane.persistence.IdempotencyKeyRecord;
import io.agentteams.controlplane.persistence.IdempotencyKeyRepository;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.persistence.TeamRepository;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.time.Instant;
import java.util.List;
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
class TeamServiceScopeTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
            Set.of("agent:read", "agent:write"));

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

    private TeamService service;

    @BeforeEach
    void setUp() {
        service = new TeamService(persistence, new io.agentteams.controlplane.team.TeamSchedulingPolicy(),
                resourceScopes);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void bindsAuthenticatedTeamCreationToTheCallerScope() {
        TeamPolicyRecord policy = new TeamPolicyRecord(UUID.randomUUID(), 2, false, List.of("qwenpaw"), List.of(),
                NOW, 0);
        when(transaction.teams()).thenReturn(teams);
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });

        TeamRecord created = service.create("team-a", "Team A", policy,
                NOW);

        verify(resourceScopes).bind("TEAM", created.id(), PRINCIPAL, NOW);
        verify(teams).insert(created);
        verify(teams).insertPolicy(any(TeamPolicyRecord.class));
    }

    @Test
    void rejectsAuthenticatedTeamOperationOutsideTheCallerScope() {
        UUID teamId = UUID.randomUUID();
        doThrow(new AuthorizationException("resource is outside caller project"))
                .when(resourceScopes).requireVisible("TEAM", teamId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.canAssign(teamId, new io.agentteams.controlplane.team.TeamSchedulingPolicy.AssignmentRequest(
                        UUID.randomUUID(), List.of(), false)))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("resource is outside caller project");
    }

    @Test
    void failsClosedWhenResourceScopeRepositoryIsUnavailableForListing() {
        TeamService withoutScopes = new TeamService(persistence,
                new io.agentteams.controlplane.team.TeamSchedulingPolicy(), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(withoutScopes::list)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource scope");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> withoutScopes.list(
                new io.agentteams.controlplane.api.CursorPageRequest(null, 20, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource scope");
    }

    @Test
    void rejectsLegacyListingWithoutAnAuthenticatedPrincipal() {
        PrincipalContext.clear();

        org.assertj.core.api.Assertions.assertThatThrownBy(service::list)
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void rejectsIndividualVisibilityChecksWithoutAnAuthenticatedPrincipal() {
        PrincipalContext.clear();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(UUID.randomUUID()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void usesScopedRepositoryForLegacyListing() {
        TeamRecord team = new TeamRecord(UUID.randomUUID(), "team", "Team", "ACTIVE", NOW, NOW, 0);
        when(transaction.teams()).thenReturn(teams);
        when(teams.findAll(PRINCIPAL)).thenReturn(List.of(team));
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });
        assertThat(service.list()).containsExactly(team);
        verify(teams).findAll(PRINCIPAL);
    }

    @Test
    void memberMutationWithSameKeyAndRequestReturnsTheExistingMember() {
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        TeamMemberRecord member = new TeamMemberRecord(UUID.randomUUID(), teamId, agentId, "MEMBER", "ACTIVE",
                NOW, NOW, 0);
        when(transaction.teams()).thenReturn(teams);
        when(transaction.agents()).thenReturn(agents);
        when(transaction.idempotencyKeys()).thenReturn(idempotencyKeys);
        when(teams.findById(teamId)).thenReturn(Optional.of(new TeamRecord(teamId, "team", "Team", "ACTIVE", NOW, NOW, 0)));
        when(agents.findById(agentId)).thenReturn(Optional.of(AgentRecord.create(agentId, "agent",
                io.agentteams.domain.agent.AgentPhase.PROVISIONING, "qwenpaw", "{}", NOW)));
        when(teams.findActiveMember(teamId, agentId)).thenReturn(Optional.of(member));
        when(idempotencyKeys.findByKey("member-key")).thenReturn(Optional.empty(),
                Optional.of(idempotency("member-key", "TEAM_ADD_MEMBER",
                        new IdempotencyService().requestHash(teamId.toString(), agentId.toString(), "MEMBER"), member.id())));
        when(idempotencyKeys.insertIfAbsent(any())).thenReturn(true);
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });

        TeamService keyed = new TeamService(persistence, new io.agentteams.controlplane.team.TeamSchedulingPolicy(),
                resourceScopes, new IdempotencyService());

        assertThat(keyed.addMember(teamId, agentId, "MEMBER", NOW, "member-key")).isEqualTo(member);
        assertThat(keyed.addMember(teamId, agentId, "MEMBER", NOW, "member-key")).isEqualTo(member);
        verify(idempotencyKeys).insertIfAbsent(any());
    }

    @Test
    void policyMutationWithSameKeyAndRequestReturnsTheExistingPolicy() {
        UUID teamId = UUID.randomUUID();
        TeamPolicyRecord policy = new TeamPolicyRecord(teamId, 3, true, List.of("java"), List.of("gpu"), NOW, 2);
        when(transaction.teams()).thenReturn(teams);
        when(transaction.idempotencyKeys()).thenReturn(idempotencyKeys);
        when(teams.findById(teamId)).thenReturn(Optional.of(new TeamRecord(teamId, "team", "Team", "ACTIVE", NOW, NOW, 0)));
        when(teams.findPolicy(teamId)).thenReturn(Optional.of(policy));
        String hash = new IdempotencyService().requestHash(teamId.toString(), "3", "true", "[java]", "[gpu]", "2");
        when(idempotencyKeys.findByKey("policy-key")).thenReturn(Optional.empty(),
                Optional.of(idempotency("policy-key", "TEAM_UPDATE_POLICY", hash, teamId)));
        when(idempotencyKeys.insertIfAbsent(any())).thenReturn(true);
        when(teams.updatePolicy(any(), eq(2L))).thenReturn(policy);
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });

        TeamService keyed = new TeamService(persistence, new io.agentteams.controlplane.team.TeamSchedulingPolicy(),
                resourceScopes, new IdempotencyService());

        assertThat(keyed.updatePolicy(teamId, 3, true, List.of("java"), List.of("gpu"), 2, NOW, "policy-key"))
                .isEqualTo(policy);
        assertThat(keyed.updatePolicy(teamId, 3, true, List.of("java"), List.of("gpu"), 2, NOW, "policy-key"))
                .isEqualTo(policy);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> keyed.updatePolicy(teamId, 4, true,
                List.of("java"), List.of("gpu"), 2, NOW, "policy-key"))
                .isInstanceOf(io.agentteams.controlplane.persistence.IdempotencyConflictException.class);
        verify(idempotencyKeys).insertIfAbsent(any());
    }

    private static IdempotencyKeyRecord idempotency(String key, String operation, String hash, UUID resourceId) {
        return new IdempotencyKeyRecord(UUID.randomUUID(), key, operation, hash, "team", resourceId, "{}", NOW, NOW, 0);
    }
}
