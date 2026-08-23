package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelPriceCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private FoundationPersistenceService persistence;

    @Mock
    private AuditRecorder auditRecorder;

    private ModelPriceCatalogService service;

    @BeforeEach
    void setUp() {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("model:read", "model:write")));
        service = new ModelPriceCatalogService(persistence, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC), auditRecorder);
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void createsAProjectScopedPriceAndAuditsOnlyOutcomeAndResourceId() {
        when(persistence.createModelPrice(any(ModelPriceRecord.class), eq("price-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelPriceRecord result = service.createPrice("price-key",
                new ModelPriceCatalogService.PriceInput(" openai ", " gpt-4o ", "usd",
                        new BigDecimal("2.5"), new BigDecimal("10"), NOW, null, "ACTIVE"));

        assertThat(result.tenantId()).isEqualTo("tenant-a");
        assertThat(result.projectId()).isEqualTo("project-a");
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.lifecycleStatus()).isEqualTo("ACTIVE");
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().attributes()).containsEntry("result", "SUCCESS");
        assertThat(audit.getValue().attributes()).doesNotContainKey("credentialRef");
    }

    @Test
    void refusesToReadOrWritePricesWithoutAnAuthenticatedScope() {
        PrincipalContext.clear();

        assertThatThrownBy(() -> service.listPrices())
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class);
        assertThatThrownBy(() -> service.createPrice("price-key",
                new ModelPriceCatalogService.PriceInput("openai", "gpt-4o", "USD",
                        BigDecimal.ONE, BigDecimal.ONE, NOW, null, "DRAFT")))
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class);
        verify(persistence, never()).findModelPrices(any(), any());
        verify(persistence, never()).createModelPrice(any(), any(), any());
    }

    @Test
    void effectiveLookupAlwaysUsesTheAuthenticatedTenantAndProject() {
        when(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", NOW)).thenReturn(Optional.empty());

        assertThat(service.findEffectivePrice("openai", "gpt-4o", "usd", NOW)).isEmpty();
        verify(persistence).findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", NOW);
    }

    @Test
    void explicitProjectScopeIsPropagatedToThePersistenceBoundary() {
        AuthorizationService.Scope scope = new AuthorizationService.Scope("tenant-a", "project-a", "team-b");
        when(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", NOW)).thenReturn(Optional.empty());

        assertThat(service.findEffectivePrice(scope, "openai", "gpt-4o", "USD", NOW)).isEmpty();
        verify(persistence).findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", NOW);
    }

    @Test
    void rejectsAnExplicitScopeOutsideTheAuthenticatedProject() {
        AuthorizationService.Scope otherProject = new AuthorizationService.Scope(
                "tenant-a", "project-b", "team-a");

        assertThatThrownBy(() -> service.findEffectivePrice(otherProject, "openai", "gpt-4o", "USD", NOW))
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class)
                .hasMessage("model price is outside the caller project scope");
        verify(persistence, never()).findEffectiveModelPrice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void adaptsTheProjectPriceToManagersPerTokenReadOnlyCatalog() {
        when(persistence.findEffectiveModelPrice("tenant-a", "project-a", "openai", "gpt-4o",
                "USD", NOW)).thenReturn(Optional.of(price(UUID.randomUUID(), "ACTIVE", 3)));

        io.agentteams.manager.ModelPriceCatalog managerCatalog = service.managerCatalog(
                new AuthorizationService.Scope("tenant-a", "project-a", "another-team"));

        assertThat(managerCatalog.find("openai", "gpt-4o", "usd")).hasValueSatisfying(price -> {
            assertThat(price.inputPricePerToken()).isEqualByComparingTo("0.0000025");
            assertThat(price.outputPricePerToken()).isEqualByComparingTo("0.00001");
        });
        assertThatThrownBy(() -> managerCatalog.register(new io.agentteams.manager.ModelPrice(
                "openai", "gpt-4o", "USD", BigDecimal.ONE, BigDecimal.ONE)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void leavesMissingPriceAsAnEmptyManagerLookup() {
        when(persistence.findEffectiveModelPrice("tenant-a", "project-a", "unknown", "model",
                "USD", NOW)).thenReturn(Optional.empty());

        io.agentteams.manager.ModelPriceCatalog managerCatalog = service.managerCatalog(
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"));

        assertThat(managerCatalog.find("unknown", "model", "USD")).isEmpty();
    }

    @Test
    void lifecycleWritesAreVersionedByThePersistenceBoundary() {
        UUID id = UUID.randomUUID();
        ModelPriceRecord current = price(id, "DRAFT", 0);
        when(persistence.findModelPrice(id, "tenant-a", "project-a")).thenReturn(Optional.of(current));
        when(persistence.updateModelPriceLifecycle(id, "tenant-a", "project-a", "ACTIVE", NOW, "alice"))
                .thenReturn(price(id, "ACTIVE", 1));

        assertThat(service.setLifecycle(id, "active").lifecycleStatus()).isEqualTo("ACTIVE");
        verify(persistence).updateModelPriceLifecycle(id, "tenant-a", "project-a", "ACTIVE", NOW, "alice");
    }

    private static ModelPriceRecord price(UUID id, String lifecycle, long version) {
        return new ModelPriceRecord(id, "tenant-a", "project-a", "openai", "gpt-4o", "USD",
                new BigDecimal("2.5"), BigDecimal.TEN, NOW, null, lifecycle, NOW, NOW, version, "alice", "alice");
    }
}
