package io.agentteams.controlplane.config;

import java.util.Objects;

public record EffectiveConfig(String canonicalManifest, String sha256, ConfigProvenance provenance) {
    public EffectiveConfig {
        if (canonicalManifest == null || canonicalManifest.isBlank()) {
            throw new IllegalArgumentException("canonicalManifest must not be blank");
        }
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 must not be blank");
        Objects.requireNonNull(provenance, "provenance");
    }
}
