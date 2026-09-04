package io.agentteams.application.api;

import java.util.Optional;

/**
 * Source of provider/model/currency-specific token prices.
 *
 * <p>A missing entry is represented by {@link Optional#empty()} so callers
 * can distinguish an unpriced call from a zero-cost call.
 */
public interface ModelPriceCatalog {
    void register(ModelPrice price);

    Optional<ModelPrice> find(String provider, String model, String currency);
}
