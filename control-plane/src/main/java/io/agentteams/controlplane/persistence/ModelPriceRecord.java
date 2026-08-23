package io.agentteams.controlplane.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A project-scoped, versioned price for one provider/model/currency tuple. */
public record ModelPriceRecord(
        UUID id,
        String tenantId,
        String projectId,
        String provider,
        String model,
        String currency,
        BigDecimal inputPricePerMillionTokens,
        BigDecimal outputPricePerMillionTokens,
        Instant effectiveFrom,
        Instant effectiveTo,
        String lifecycleStatus,
        Instant createdAt,
        Instant updatedAt,
        long version,
        String createdBy,
        String updatedBy) {

    private static final Set<String> LIFECYCLE_STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");

    public ModelPriceRecord {
        Objects.requireNonNull(id, "id");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
        provider = required(provider, "provider");
        model = required(model, "model");
        currency = required(currency, "currency").toUpperCase(Locale.ROOT);
        if (currency.length() != 3 || !currency.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        inputPricePerMillionTokens = nonNegative(inputPricePerMillionTokens,
                "inputPricePerMillionTokens");
        outputPricePerMillionTokens = nonNegative(outputPricePerMillionTokens,
                "outputPricePerMillionTokens");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
        lifecycleStatus = required(lifecycleStatus, "lifecycleStatus").toUpperCase(Locale.ROOT);
        if (!LIFECYCLE_STATUSES.contains(lifecycleStatus)) {
            throw new IllegalArgumentException("lifecycleStatus must be DRAFT, ACTIVE, or RETIRED");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        createdBy = required(createdBy, "createdBy");
        updatedBy = required(updatedBy, "updatedBy");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public static Set<String> lifecycleStatuses() {
        return LIFECYCLE_STATUSES;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
