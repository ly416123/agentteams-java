package io.agentteams.controlplane.service;

/** A stable catalog conflict classification for lifecycle operations. */
public final class ModelCatalogDependencyException extends RuntimeException {

    private final String code;

    public ModelCatalogDependencyException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
