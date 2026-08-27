package io.agentteams.controlplane.security;

import java.util.Objects;

/** Metadata-only status of an ExternalSecret resource. */
public record ExternalSecretStatus(State state, String targetSecretName, String generation,
        String observedGeneration) {
    public ExternalSecretStatus(State state, String targetSecretName, String generation) {
        this(state, targetSecretName, generation, "");
    }

    public ExternalSecretStatus {
        Objects.requireNonNull(state, "state");
        targetSecretName = targetSecretName == null ? "" : targetSecretName.trim();
        generation = generation == null ? "" : generation.trim();
        observedGeneration = observedGeneration == null ? "" : observedGeneration.trim();
    }

    public boolean generationIsCurrent() {
        return generation.isBlank() || observedGeneration.isBlank()
                || generation.equals(observedGeneration);
    }

    public enum State {
        READY,
        NOT_FOUND,
        NOT_READY,
        UNKNOWN
    }
}
