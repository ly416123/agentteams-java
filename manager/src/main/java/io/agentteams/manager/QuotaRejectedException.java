package io.agentteams.manager;

/**
 * Contract exception for a quota implementation that cannot reserve a call.
 * Control-plane adapters translate their storage-specific exception to this
 * type so manager remains independent of the control-plane module.
 */
public class QuotaRejectedException extends RuntimeException {
    private final String dimension;

    public QuotaRejectedException(String dimension) {
        this(dimension, null);
    }

    public QuotaRejectedException(String dimension, Throwable cause) {
        super("quota rejected" + (dimension == null || dimension.isBlank() ? "" : ": " + dimension), cause);
        this.dimension = dimension;
    }

    public String dimension() {
        return dimension;
    }
}
