package io.agentteams.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable configuration for the QwenPaw JSON Lines process boundary. */
public record QwenPawProcessConfiguration(List<String> command, Path workingDirectory,
        Map<String, String> environment, Duration shutdownTimeout) {
    public QwenPawProcessConfiguration {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain at least one non-blank value");
        }
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
    }

    public static QwenPawProcessConfiguration of(List<String> command) {
        return new QwenPawProcessConfiguration(command, null, Map.of(), Duration.ofSeconds(5));
    }
}
