package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentServiceScopeTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
            Set.of("agent:read", "agent:write"));

    @Mock
    private FoundationPersistenceService persistence;

    @Mock
    private ResourceScopeRepository resourceScopes;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(persistence, new IdempotencyService(), Clock.fixed(NOW, ZoneOffset.UTC),
                resourceScopes);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void bindsAuthenticatedWorkerCreationToTheCallerScope() {
        AgentRecord created = AgentRecord.create(UUID.randomUUID(), "worker-1", AgentPhase.PROVISIONING,
                "qwenpaw", "{}", NOW);
        when(persistence.createAgent(any(AgentRecord.class), eq("agent-key"), any())).thenReturn(created);

        service.create("agent-key", new AgentService.AgentInput("worker-1", "qwenpaw", "{}", "{}"));

        verify(resourceScopes).bind("WORKER", created.id(), PRINCIPAL, NOW);
    }

    @Test
    void rejectsAuthenticatedReadOutsideTheCallerScope() {
        UUID workerId = UUID.randomUUID();
        AgentRecord worker = AgentRecord.create(workerId, "worker-1", AgentPhase.READY,
                "qwenpaw", "{}", NOW);
        when(persistence.findAgent(workerId)).thenReturn(java.util.Optional.of(worker));
        doThrow(new AuthorizationException("resource is outside caller project"))
                .when(resourceScopes).requireVisible("WORKER", workerId);

        assertThatThrownBy(() -> service.get(workerId))
                .isInstanceOf(AuthorizationException.class)
                .hasMessage("resource is outside caller project");
    }
}
