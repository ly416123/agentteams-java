package io.agentteams.controlplane.quota;

/** Raised when a configured project quota cannot accept another call. */
public final class QuotaExceededException extends RuntimeException {
    private final String dimension;

    public QuotaExceededException(String dimension) {
        super("project quota exceeded: " + dimension);
        this.dimension = dimension;
    }

    public String dimension() {
        return dimension;
    }
}
