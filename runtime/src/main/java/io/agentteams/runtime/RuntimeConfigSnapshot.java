package io.agentteams.runtime;

import io.agentteams.application.api.SkillCapabilityPolicy;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Path;

/** Immutable, versioned configuration data supplied to a runtime. */
public record RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values,
        Map<String, Path> files, Map<String, Path> skillDirectories,
        Map<String, RuntimeMcpServer> mcpServers,
        Map<String, SkillCapabilityPolicy> skillCapabilities) {
    public RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values) {
        this(version, checksum, values, Map.of(), Map.of(), Map.of());
    }

    public RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values,
            Map<String, Path> files) {
        this(version, checksum, values, files, Map.of(), Map.of());
    }

    public RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values,
            Map<String, Path> files, Map<String, Path> skillDirectories) {
        this(version, checksum, values, files, skillDirectories, Map.of());
    }

    public RuntimeConfigSnapshot(long version, String checksum, Map<String, String> values,
            Map<String, Path> files, Map<String, Path> skillDirectories,
            Map<String, RuntimeMcpServer> mcpServers) {
        this(version, checksum, values, files, skillDirectories, mcpServers, Map.of());
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
        skillDirectories = Map.copyOf(Objects.requireNonNull(skillDirectories, "skillDirectories"));
        mcpServers = Map.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
        skillCapabilities = Map.copyOf(Objects.requireNonNull(skillCapabilities, "skillCapabilities"));
        files.forEach((path, file) -> {
            if (path == null || path.isBlank()) throw new IllegalArgumentException("file path must not be blank");
            Objects.requireNonNull(file, "file");
        });
        skillDirectories.forEach((key, directory) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("skill directory key must not be blank");
            Objects.requireNonNull(directory, "skill directory");
        });
        mcpServers.forEach((key, server) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("MCP server key must not be blank");
            Objects.requireNonNull(server, "MCP server");
        });
        skillCapabilities.forEach((key, policy) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Skill capability key must not be blank");
            Objects.requireNonNull(policy, "Skill capability policy");
        });
    }
}
