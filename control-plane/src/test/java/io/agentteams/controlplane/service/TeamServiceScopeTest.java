package io.agentteams.controlplane.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
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
}
