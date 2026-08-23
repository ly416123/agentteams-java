package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import java.time.Instant;
import java.util.Optional;

/** Internal lookup boundary for cost consumers; it never reaches a model runtime. */
public interface ModelPriceCatalogPort {
    Optional<ModelPriceRecord> findEffectivePrice(String provider, String model, String currency, Instant at);

    /**
     * Looks up a price for an explicitly propagated project scope.
     *
     * <p>Adapters which cross the Control Plane/Manager boundary must use this
     * overload so a project cannot be silently selected from ambient state.
     * Implementations must reject a scope that is outside the authenticated
     * caller scope.</p>
     */
    Optional<ModelPriceRecord> findEffectivePrice(AuthorizationService.Scope scope, String provider,
            String model, String currency, Instant at);
}
