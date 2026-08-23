package io.agentteams.manager;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An estimated cost for measured token usage.
 *
 * <p>This type deliberately represents an estimate only; it is not a final
 * invoice or a provider billing record. For an unpriced call, cost fields are
 * absent and {@link #status()} explains why no amount was produced.
 */
public record ModelCostEstimate(String provider, String model, String currency,
        ModelTokenUsage tokenUsage, Status status, BigDecimal inputCost,
        BigDecimal outputCost, BigDecimal estimatedCost, String reason) {
    public enum Status { ESTIMATED, UNPRICED }

    public ModelCostEstimate {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        currency = ModelPrice.normalizeCurrency(currency);
        Objects.requireNonNull(tokenUsage, "tokenUsage");
        Objects.requireNonNull(status, "status");
        if (status == Status.ESTIMATED) {
            requireNonNegative(inputCost, "inputCost");
            requireNonNegative(outputCost, "outputCost");
            requireNonNegative(estimatedCost, "estimatedCost");
            if (reason != null && !reason.isBlank()) {
                throw new IllegalArgumentException("reason must be blank for an estimated result");
            }
        } else {
            if (inputCost != null || outputCost != null || estimatedCost != null) {
                throw new IllegalArgumentException("unpriced result must not contain cost amounts");
            }
            reason = requireText(reason, "reason");
        }
    }

    public static ModelCostEstimate unpriced(String provider, String model, String currency,
            ModelTokenUsage tokenUsage) {
        return new ModelCostEstimate(provider, model, currency, tokenUsage, Status.UNPRICED,
                null, null, null, "No price configured for provider/model/currency");
    }

    public boolean isPriced() {
        return status == Status.ESTIMATED;
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null for an estimated result");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
