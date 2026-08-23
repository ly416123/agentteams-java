package io.agentteams.runtime;

/** Stable exception boundary for a quota port refusal. */
public class RuntimeQuotaRejectedException extends RuntimeException {
    private final String dimension;

    public RuntimeQuotaRejectedException(String dimension) {
        this(dimension, null);
    }

    public RuntimeQuotaRejectedException(String dimension, Throwable cause) {
        super("quota rejected" + (dimension == null || dimension.isBlank() ? "" : ": " + dimension), cause);
        this.dimension = dimension;
    }

    public String dimension() {
        return dimension;
    }
}
