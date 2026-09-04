package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only adapter from the project-scoped Control Plane catalog to Manager's
 * provider/model/currency price boundary.
 *
 * <p>Control Plane stores display prices per million tokens while Manager
 * calculates with per-token prices. The adapter performs that unit conversion
 * and deliberately preserves an absent price as {@code Optional.empty()}, so
 * Manager can produce its explicit {@code UNPRICED} result.</p>
 */
public final class ManagerModelPriceCatalogAdapter implements io.agentteams.application.api.ModelPriceCatalog {

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);

    private final ModelPriceCatalogPort catalog;
    private final AuthorizationService.Scope scope;
    private final Clock clock;

    public ManagerModelPriceCatalogAdapter(ModelPriceCatalogPort catalog,
            AuthorizationService.Scope scope, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The Control Plane owns writes because they require idempotency and audit context. */
    @Override
    public void register(io.agentteams.application.api.ModelPrice price) {
        throw new UnsupportedOperationException("Control Plane price catalog is read-only in Manager");
    }

    @Override
    public Optional<io.agentteams.application.api.ModelPrice> find(String provider, String model, String currency) {
        return catalog.findEffectivePrice(scope, provider, model, currency, clock.instant())
                .map(ManagerModelPriceCatalogAdapter::toManagerPrice);
    }

    private static io.agentteams.application.api.ModelPrice toManagerPrice(ModelPriceRecord price) {
        return new io.agentteams.application.api.ModelPrice(price.provider(), price.model(), price.currency(),
                price.inputPricePerMillionTokens().divide(TOKENS_PER_MILLION),
                price.outputPricePerMillionTokens().divide(TOKENS_PER_MILLION));
    }
}
