package io.agentteams.controlplane.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Outbound boundary for retrieving an unscoped, provider-owned model price snapshot. */
public interface ModelPriceSyncPort {
    Snapshot fetch();

    record Snapshot(String sourceVersion, List<Quote> quotes) {
        public Snapshot {
            sourceVersion = sourceVersion == null || sourceVersion.isBlank() ? "unspecified" : sourceVersion.trim();
            if (sourceVersion.length() > 256) throw new IllegalArgumentException("sourceVersion is too long");
            quotes = List.copyOf(Objects.requireNonNull(quotes, "quotes"));
        }
    }

    record Quote(String provider, String model, String currency, BigDecimal inputPricePerMillionTokens,
            BigDecimal outputPricePerMillionTokens, Instant effectiveFrom, Instant effectiveTo) {
        public Quote {
            provider = required(provider, "provider");
            model = required(model, "model");
            currency = required(currency, "currency").toUpperCase(Locale.ROOT);
            if (currency.length() != 3 || !currency.chars().allMatch(Character::isLetter)) {
                throw new IllegalArgumentException("currency must be a three-letter code");
            }
            inputPricePerMillionTokens = nonNegative(inputPricePerMillionTokens, "inputPricePerMillionTokens");
            outputPricePerMillionTokens = nonNegative(outputPricePerMillionTokens, "outputPricePerMillionTokens");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
            }
        }

        private static BigDecimal nonNegative(BigDecimal value, String field) {
            Objects.requireNonNull(value, field);
            if (value.signum() < 0) throw new IllegalArgumentException(field + " must not be negative");
            return value;
        }

        private static String required(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
            return value.trim();
        }
    }
}
