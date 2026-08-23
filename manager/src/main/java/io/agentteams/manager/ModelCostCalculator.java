package io.agentteams.manager;

import java.math.BigDecimal;
import java.util.Objects;

/** Calculates token-based estimates without turning them into billing data. */
public final class ModelCostCalculator {
    private final ModelPriceCatalog catalog;

    public ModelCostCalculator(ModelPriceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public ModelCostEstimate estimate(String provider, String model, String currency,
            ModelTokenUsage tokenUsage) {
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        String normalizedProvider = requireText(provider, "provider");
        String normalizedModel = requireText(model, "model");
        String normalizedCurrency = ModelPrice.normalizeCurrency(currency);

        return catalog.find(normalizedProvider, normalizedModel, normalizedCurrency)
                .map(price -> calculate(price, tokenUsage))
                .orElseGet(() -> ModelCostEstimate.unpriced(normalizedProvider, normalizedModel,
                        normalizedCurrency, tokenUsage));
    }

    public ModelCostEstimate estimate(String provider, String model, String currency,
            long inputTokens, long outputTokens) {
        return estimate(provider, model, currency, new ModelTokenUsage(inputTokens, outputTokens));
    }

    private static ModelCostEstimate calculate(ModelPrice price, ModelTokenUsage usage) {
        BigDecimal inputCost = BigDecimal.valueOf(usage.inputTokens()).multiply(price.inputPricePerToken());
        BigDecimal outputCost = BigDecimal.valueOf(usage.outputTokens()).multiply(price.outputPricePerToken());
        return new ModelCostEstimate(price.provider(), price.model(), price.currency(), usage,
                ModelCostEstimate.Status.ESTIMATED, inputCost, outputCost,
                inputCost.add(outputCost), null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
