package io.agentteams.runtime;

import java.util.Map;
import java.util.Objects;

/** Immutable, versioned configuration data supplied to a runtime. */
public record RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values) {
    public RuntimeConfigSnapshot {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }
}
