package io.agentteams.controlplane.service;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class ModelPriceCatalogService implements ModelPriceCatalogPort {

    private final FoundationPersistenceService persistence;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final AuditRecorder auditRecorder;

    public ModelPriceCatalogService(FoundationPersistenceService persistence, IdempotencyService idempotency) {
        this(persistence, idempotency, Clock.systemUTC(), event -> { });
    }

    @Autowired
    public ModelPriceCatalogService(FoundationPersistenceService persistence, IdempotencyService idempotency,
            Clock clock, AuditRecorder auditRecorder) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    }

    public ModelPriceRecord createPrice(String idempotencyKey, PriceInput input) {
        AuthorizationService.Scope scope = requiredScope();
        String actor = PrincipalContext.actorOr("internal");
        UUID resourceId = UUID.randomUUID();
        try {
            Objects.requireNonNull(input, "input");
            String key = idempotency.requireKey(idempotencyKey);
            String provider = required(input.provider(), "provider");
            String model = required(input.model(), "model");
            String currency = required(input.currency(), "currency").toUpperCase(Locale.ROOT);
            String lifecycle = lifecycle(input.lifecycleStatus());
            Instant effectiveFrom = Objects.requireNonNull(input.effectiveFrom(), "effectiveFrom");
            Instant effectiveTo = input.effectiveTo();
            Instant now = clock.instant();
            ModelPriceRecord price = new ModelPriceRecord(resourceId, scope.tenant(), scope.project(), provider,
                    model, currency, input.inputPricePerMillionTokens(), input.outputPricePerMillionTokens(),
                    effectiveFrom, effectiveTo, lifecycle, now, now, 0, actor, actor);
            ModelPriceRecord result = persistence.createModelPrice(price, key, idempotency.requestHash(
                    scope.tenant(), scope.project(), provider, model, currency,
                    price.inputPricePerMillionTokens().toPlainString(),
                    price.outputPricePerMillionTokens().toPlainString(), effectiveFrom.toString(),
                    effectiveTo == null ? null : effectiveTo.toString(), lifecycle));
            record(actor, "CREATE_MODEL_PRICE", result.id(), "SUCCESS");
            return result;
        } catch (RuntimeException error) {
            record(actor, "CREATE_MODEL_PRICE", resourceId, "FAILURE");
            throw error;
        }
    }

    public List<ModelPriceRecord> listPrices() {
        AuthorizationService.Scope scope = requiredScope();
        return persistence.findModelPrices(scope.tenant(), scope.project());
    }

    public ModelPriceRecord getPrice(UUID id) {
        AuthorizationService.Scope scope = requiredScope();
        return persistence.findModelPrice(Objects.requireNonNull(id, "id"), scope.tenant(), scope.project())
                .orElseThrow(() -> new ResourceNotFoundException("model price", id));
    }

    @Override
    public Optional<ModelPriceRecord> findEffectivePrice(String provider, String model, String currency,
            Instant at) {
        AuthorizationService.Scope scope = requiredScope();
        return findEffectivePrice(scope, provider, model, currency, at);
    }

    @Override
    public Optional<ModelPriceRecord> findEffectivePrice(AuthorizationService.Scope scope, String provider,
            String model, String currency, Instant at) {
        AuthorizationService.Scope requestedScope = Objects.requireNonNull(scope, "scope");
        requireProjectScope(requestedScope);
        String normalizedProvider = required(provider, "provider");
        String normalizedModel = required(model, "model");
        String normalizedCurrency = required(currency, "currency").toUpperCase(Locale.ROOT);
        return persistence.findEffectiveModelPrice(requestedScope.tenant(), requestedScope.project(),
                normalizedProvider,
                normalizedModel, normalizedCurrency, Objects.requireNonNull(at, "at"));
    }

    public Optional<ModelPriceRecord> findEffectivePrice(String provider, String model, String currency) {
        return findEffectivePrice(provider, model, currency, clock.instant());
    }

    /**
     * Creates the read-only catalog view expected by Manager.
     *
     * <p>The scope is captured once for the Manager session/configuration and
     * is checked again on every lookup by the underlying service. This keeps
     * project propagation explicit while preserving Manager's existing
     * provider/model/currency lookup shape.</p>
     */
    public io.agentteams.application.api.ModelPriceCatalog managerCatalog(AuthorizationService.Scope scope) {
        return new ManagerModelPriceCatalogAdapter(this, scope, clock);
    }

    public ModelPriceRecord setLifecycle(UUID id, String lifecycleStatus) {
        AuthorizationService.Scope scope = requiredScope();
        String normalizedLifecycle = lifecycle(lifecycleStatus);
        ModelPriceRecord current = getPrice(id);
        ModelPriceRecord result = persistence.updateModelPriceLifecycle(current.id(), scope.tenant(),
                scope.project(), normalizedLifecycle, clock.instant(), PrincipalContext.actorOr("internal"));
        record(PrincipalContext.actorOr("internal"), "SET_MODEL_PRICE_LIFECYCLE", result.id(),
                normalizedLifecycle);
        return result;
    }

    public record PriceInput(String provider, String model, String currency,
            BigDecimal inputPricePerMillionTokens, BigDecimal outputPricePerMillionTokens,
            Instant effectiveFrom, Instant effectiveTo, String lifecycleStatus) { }

    private static AuthorizationService.Scope requiredScope() {
        return PrincipalContext.current().map(principal -> principal.scope()).orElseThrow(
                () -> new AuthorizationException("authenticated tenant and project scope is required"));
    }

    private static void requireProjectScope(AuthorizationService.Scope requested) {
        AuthorizationService.Scope caller = requiredScope();
        if (!caller.tenant().equals(requested.tenant())
                || !caller.project().equals(requested.project())) {
            throw new AuthorizationException("model price is outside the caller project scope");
        }
    }

    private static String lifecycle(String value) {
        String normalized = value == null || value.isBlank() ? "DRAFT" : value.trim().toUpperCase(Locale.ROOT);
        if (!ModelPriceRecord.lifecycleStatuses().contains(normalized)) {
            throw new IllegalArgumentException("lifecycleStatus must be DRAFT, ACTIVE, or RETIRED");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private void record(String actor, String action, UUID resourceId, String result) {
        try {
            auditRecorder.record(new AuditEvent(UUID.randomUUID(), actor, action, "model_price",
                    resourceId.toString(), Map.of("result", result), clock.instant()));
        } catch (RuntimeException ignored) {
            // Audit is best effort and contains only the price resource id and outcome.
        }
    }
}
