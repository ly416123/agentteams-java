package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** Serializable envelope used by Gateway to return configuration acknowledgements. */
public record ConfigAppliedEnvelope(int schemaVersion, String type, UUID eventId, UUID bindingId,
        UUID snapshotId, UUID agentId, long configVersion, boolean applied, String errorMessage,
        Instant occurredAt, String source, String correlationId, List<ConfigEventPort.ResourceApplyResult> resourceResults) {

    public ConfigAppliedEnvelope {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        if (!"CONFIG_APPLIED".equals(type)) throw new IllegalArgumentException("unsupported config event type");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(agentId, "agentId");
        if (configVersion <= 0) throw new IllegalArgumentException("configVersion must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        errorMessage = errorMessage == null ? "" : errorMessage;
        correlationId = correlationId == null || correlationId.isBlank() ? "unknown" : correlationId;
        resourceResults = resourceResults == null ? List.of() : List.copyOf(resourceResults);
    }

    public static ConfigAppliedEnvelope from(ConfigEventPort.ConfigAppliedCommand command) {
        return new ConfigAppliedEnvelope(1, "CONFIG_APPLIED", command.eventId(), command.bindingId(),
                command.snapshotId(), command.agentId(), command.configVersion(), command.applied(),
                command.errorMessage(), command.occurredAt(), command.source(), command.correlationId(),
                command.resourceResults());
    }
}
