package io.agentteams.manager;

import io.agentteams.application.api.ModelPrice;
import io.agentteams.application.api.ModelPriceCatalog;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory implementation of {@link ModelPriceCatalog}. */
public final class InMemoryModelPriceCatalog implements ModelPriceCatalog {
    private final Map<Key, ModelPrice> prices = new ConcurrentHashMap<>();

    public InMemoryModelPriceCatalog() {
    }

    public InMemoryModelPriceCatalog(Collection<ModelPrice> initialPrices) {
        Objects.requireNonNull(initialPrices, "initialPrices");
        initialPrices.forEach(this::register);
    }

    @Override
    public void register(ModelPrice price) {
        Objects.requireNonNull(price, "price");
        prices.put(Key.from(price), price);
    }

    @Override
    public Optional<ModelPrice> find(String provider, String model, String currency) {
        return Optional.ofNullable(prices.get(new Key(requireText(provider, "provider"),
                requireText(model, "model"), ModelPrice.normalizeCurrency(currency))));
    }

    public int size() {
        return prices.size();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record Key(String provider, String model, String currency) {
        private static Key from(ModelPrice price) {
            return new Key(price.provider(), price.model(), price.currency());
        }
    }
}
