package io.agentteams.controlplane.usage;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Project-scoped budget policy and its durable evaluation read model. */
public record UsageBudgetPolicy(UUID id, String tenantId, String projectId, String currency,
        Duration period, BigDecimal softThreshold, BigDecimal hardThreshold, Duration forecastWindow,
        Status status, Instant createdAt, Instant updatedAt, long version) {

    public enum Status { ACTIVE, PAUSED }

    public UsageBudgetPolicy {
        Objects.requireNonNull(id, "id");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
        currency = required(currency, "currency").toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a 3-letter code");
        period = Objects.requireNonNull(period, "period");
        if (period.compareTo(Duration.ofHours(1)) < 0) throw new IllegalArgumentException("period must be at least 1 hour");
        softThreshold = nonNegative(softThreshold, "softThreshold");
        hardThreshold = nonNegative(hardThreshold, "hardThreshold");
        if (softThreshold.compareTo(hardThreshold) > 0) {
            throw new IllegalArgumentException("softThreshold must not exceed hardThreshold");
        }
        forecastWindow = Objects.requireNonNull(forecastWindow, "forecastWindow");
        if (forecastWindow.isZero() || forecastWindow.isNegative()) {
            throw new IllegalArgumentException("forecastWindow must be positive");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }
}
