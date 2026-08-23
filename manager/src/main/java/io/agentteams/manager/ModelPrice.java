package io.agentteams.manager;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/**
 * Price for one model in one provider and currency.
 *
 * <p>The two prices are per token. Keeping the unit explicit avoids silently
 * mixing provider price sheets that use different display units such as one
 * thousand or one million tokens.
 */
public record ModelPrice(String provider, String model, String currency,
        BigDecimal inputPricePerToken, BigDecimal outputPricePerToken) {
    public ModelPrice {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        currency = normalizeCurrency(currency);
        inputPricePerToken = requirePrice(inputPricePerToken, "inputPricePerToken");
        outputPricePerToken = requirePrice(outputPricePerToken, "outputPricePerToken");
    }

    private static BigDecimal requirePrice(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    static String normalizeCurrency(String value) {
        String normalized = requireText(value, "currency").toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("currency must be a valid ISO 4217 code: " + value, error);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
