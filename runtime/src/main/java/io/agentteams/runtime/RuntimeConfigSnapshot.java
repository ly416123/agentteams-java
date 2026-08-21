package io.agentteams.runtime;

import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

/** Immutable, versioned configuration data supplied to a runtime. */
public record RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values,
        Map<String, Path> files) {
    public RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values) {
        this(version, checksum, values, Map.of());
    }

    public RuntimeConfigSnapshot {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (checksum == null || checksum.isBlank()) {
            throw new IllegalArgumentException("checksum must not be blank");
        }
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        files = Map.copyOf(Objects.requireNonNull(files, "files"));
        files.forEach((path, file) -> {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("file path must not be blank");
            Objects.requireNonNull(file, "file");
        });
    }
}
