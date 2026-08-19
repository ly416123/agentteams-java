package io.agentteams.application.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for durable runtime configuration acknowledgements. */
public interface ConfigEventPort {

    void applied(ConfigAppliedCommand command);

    record ConfigAppliedCommand(UUID eventId, UUID bindingId, UUID snapshotId, UUID agentId,
            long configVersion, boolean applied, String errorMessage, Instant occurredAt,
            String source, String correlationId) {
        public ConfigAppliedCommand {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            requireText(source, "source");
            correlationId = correlationId == null || correlationId.isBlank() ? "unknown" : correlationId;
            if (configVersion <= 0) throw new IllegalArgumentException("configVersion must be positive");
            if (correlationId.length() > 128 || !correlationId.matches("[A-Za-z0-9._:-]+")) {
                throw new IllegalArgumentException("correlationId contains unsupported characters");
            }
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public ConfigAppliedCommand(UUID eventId, UUID bindingId, UUID snapshotId, UUID agentId,
                long configVersion, boolean applied, String errorMessage, Instant occurredAt, String source) {
            this(eventId, bindingId, snapshotId, agentId, configVersion, applied, errorMessage,
                    occurredAt, source, "unknown");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
