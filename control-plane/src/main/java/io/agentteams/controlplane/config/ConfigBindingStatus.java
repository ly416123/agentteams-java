package io.agentteams.controlplane.config;

import java.util.Objects;

/** Current desired snapshot and apply result for one configuration binding. */
public record ConfigBindingStatus(ConfigBindingRecord binding, ConfigSnapshot desiredSnapshot,
        ConfigApplyRecord apply) {
    public ConfigBindingStatus {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(desiredSnapshot, "desiredSnapshot");
    }
}
